(ns metabase.api.activity
  (:require
   [clojure.string :as str]
   [compojure.core :refer [GET]]
   [medley.core :as m]
   [metabase.api.common :as api :refer [*current-user-id* define-routes]]
   [metabase.db.util :as mdb.u]
   [metabase.models.card :refer [Card]]
   [metabase.models.dashboard :refer [Dashboard]]
   [metabase.models.interface :as mi]
   [metabase.models.query-execution :refer [QueryExecution]]
   [metabase.models.recent-views :as recent-views]
   [metabase.models.table :refer [Table]]
   [metabase.models.view-log :refer [ViewLog]]
   [metabase.util.honey-sql-2 :as h2x]
   [toucan2.core :as t2]))

(defn- models-query
  [model ids]
  (t2/select
   (case model
     "card"      [Card
                  :id :name :collection_id :description :display
                  :dataset_query :type :archived
                  :collection.authority_level]
     "dashboard" [Dashboard
                  :id :name :collection_id :description
                  :archived
                  :collection.authority_level]
     "table"     [Table
                  :id :name :db_id
                  :display_name :initial_sync_status
                  :visibility_type])
   (let [model-symb (symbol (str/capitalize model))
         self-qualify #(mdb.u/qualify model-symb %)]
     (cond-> {:where [:in (self-qualify :id) ids]}
       (not= model "table")
       (merge {:left-join [:collection [:= :collection.id (self-qualify :collection_id)]]})))))

(defn- select-items! [model ids]
  (when (seq ids)
    (for [model (t2/hydrate (models-query model ids) :moderation_reviews)
          :let [reviews (:moderation_reviews model)
                status  (->> reviews
                             (filter :most_recent)
                             first
                             :status)]]
      (assoc model :moderated_status status))))

(defn- models-for-views
  "Returns a map of {model {id instance}} for activity views suitable for looking up by model and id to get a model."
  [views]
  (into {} (map (fn [[model models]]
                  [model (->> models
                              (map :model_id)
                              (select-items! model)
                              (m/index-by :id))]))
        (group-by :model views)))

(defn- views-and-runs
  "Query implementation for `popular_items`. Tables and Dashboards have a query limit of `views-limit`.
  Cards have a query limit of `card-runs-limit`.

  The expected output of the query is a single row per unique model viewed by the current user including a `:max_ts` which
  has the most recent view timestamp of the item and `:cnt` which has total views. We order the results by most recently
  viewed then hydrate the basic details of the model. Bookmarked cards and dashboards are *not* included in the result.

  Viewing a Dashboard will add entries to the view log for all cards on that dashboard so all card views are instead derived
  from the query_execution table. The query context is always a `:question`. The results are normalized and concatenated to the
  query results for dashboard and table views."
  [views-limit card-runs-limit all-users?]
  ;; TODO update to use RecentViews instead of ViewLog
  (let [dashboard-and-table-views (t2/select [ViewLog
                                              [[:min :view_log.user_id] :user_id]
                                              :model
                                              :model_id
                                              [:%count.* :cnt]
                                              [:%max.timestamp :max_ts]]
                                             {:group-by  [:model :model_id]
                                              :where     [:and
                                                          (when-not all-users? [:= (mdb.u/qualify ViewLog :user_id) *current-user-id*])
                                                          [:in :model #{"dashboard" "table"}]
                                                          [:= :bm.id nil]]
                                              :order-by  [[:max_ts :desc] [:model :desc]]
                                              :limit     views-limit
                                              :left-join [[:dashboard_bookmark :bm]
                                                          [:and
                                                           [:= :model "dashboard"]
                                                           [:= :bm.user_id *current-user-id*]
                                                           [:= :model_id :bm.dashboard_id]]]})
        card-runs                 (->> (t2/select [QueryExecution
                                                   [:%min.executor_id :user_id]
                                                   [(mdb.u/qualify QueryExecution :card_id) :model_id]
                                                   [:%count.* :cnt]
                                                   [:%max.started_at :max_ts]]
                                                  {:group-by [(mdb.u/qualify QueryExecution :card_id) :context]
                                                   :where    [:and
                                                              (when-not all-users? [:= :executor_id *current-user-id*])
                                                              [:= :context (h2x/literal :question)]
                                                              [:= :bm.id nil]]
                                                   :order-by [[:max_ts :desc]]
                                                   :limit    card-runs-limit
                                                   :left-join [[:card_bookmark :bm]
                                                               [:and
                                                                [:= :bm.user_id *current-user-id*]
                                                                [:= (mdb.u/qualify QueryExecution :card_id) :bm.card_id]]]})
                                       (map #(dissoc % :row_count))
                                       (map #(assoc % :model "card")))]
    (->> (concat card-runs dashboard-and-table-views)
         (sort-by :max_ts)
         reverse)))

(def ^:private views-limit 8)
(def ^:private card-runs-limit 8)

(api/defendpoint GET "/recent_views"
  "Get a list of 5 things the current user has been viewing most recently."
  []
  (let [views            (recent-views/user-recent-views api/*current-user-id* 10)
        model->id->items (models-for-views views)]
    (->> (for [{:keys [model model_id] :as view-log} views
               :let
               [model-object (-> (get-in model->id->items [model model_id])
                                 (dissoc :dataset_query))]
               :when
               (and model-object
                    (mi/can-read? model-object)
                    ;; hidden tables, archived cards/dashboards
                    (not (or (:archived model-object)
                             (= (:visibility_type model-object) :hidden))))]
           (cond-> (assoc view-log :model_object model-object)
             (= (keyword (:type model-object)) :model) (assoc :model "dataset")))
         (take 5))))

(api/defendpoint GET "/most_recently_viewed_dashboard"
  "Get the most recently viewed dashboard for the current user. Returns a 204 if the user has not viewed any dashboards
   in the last 24 hours."
  []
  (if-let [dashboard-id (recent-views/most-recently-viewed-dashboard-id api/*current-user-id*)]
    (let [dashboard (-> (t2/select-one Dashboard :id dashboard-id)
                        api/check-404
                        (t2/hydrate [:collection :is_personal]))]
      (if (mi/can-read? dashboard)
        dashboard
        api/generic-204-no-content))
    api/generic-204-no-content))

(defn- official?
  "Returns true if the item belongs to an official collection. False otherwise. Assumes that `:authority_level` exists
  if the item can be placed in a collection."
  [{:keys [authority_level]}]
  (boolean
   (when authority_level
     (#{"official"} authority_level))))

(defn- verified?
  "Return true if the item is verified, false otherwise. Assumes that `:moderated_status` is hydrated."
  [{:keys [moderated_status]}]
  (= moderated_status "verified"))

(defn- score-items
  [items]
  (when (seq items)
    (let [n-items (count items)
          max-count (apply max (map :cnt items))]
      (for [[recency-pos {:keys [cnt model_object] :as item}] (zipmap (range) items)]
        (let [verified-wt 1
              official-wt 1
              recency-wt 2
              views-wt 4
              scores [;; cards and dashboards? can be 'verified' in enterprise
                      (if (verified? model_object) verified-wt 0)
                      ;; items may exist in an 'official' collection in enterprise
                      (if (official? model_object) official-wt 0)
                      ;; most recent item = 1 * recency-wt, least recent item of 10 items = 1/10 * recency-wt
                      (* (/ (- n-items recency-pos) n-items) recency-wt)
                      ;; item with highest count = 1 * views-wt, lowest = item-view-count / max-view-count * views-wt

                      ;; NOTE: the query implementation `views-and-runs` has an order-by clause using most recent timestamp
                      ;; this has an effect on the outcomes. Consider an item with a massively high viewcount but a last view by the user
                      ;; a long time ago. This may not even make it into the firs 10 items from the query, even though it might be worth showing
                      (* (/ cnt max-count) views-wt)]]
          (assoc item :score (double (reduce + scores))))))))

(def ^:private model-precedence ["dashboard" "card" "dataset" "table"])

(defn- order-items
  [items]
  (when (seq items)
      (let [groups (group-by :model items)]
        (mapcat #(get groups %) model-precedence))))

(api/defendpoint GET "/popular_items"
  "Get the list of 5 popular things for the current user. Query takes 8 and limits to 5 so that if it
  finds anything archived, deleted, etc it can usually still get 5."
  []
  ;; we can do a weighted score which incorporates:
  ;; total count -> higher = higher score
  ;; recently viewed -> more recent = higher score
  ;; official/verified -> yes = higher score
  (let [views (views-and-runs views-limit card-runs-limit true)
        model->id->items (models-for-views views)
        filtered-views (for [{:keys [model model_id] :as view-log} views
                             :let [model-object (-> (get-in model->id->items [model model_id])
                                                    (dissoc :dataset_query))]
                             :when (and model-object
                                        (mi/can-read? model-object)
                                        ;; hidden tables, archived cards/dashboards
                                        (not (or (:archived model-object)
                                                 (= (:visibility_type model-object) :hidden))))]
                         (cond-> (assoc view-log :model_object model-object)
                           (= (keyword (:type model-object)) :model) (assoc :model "dataset")))
        scored-views (score-items filtered-views)]
    (->> scored-views
         (sort-by :score)
         reverse
         order-items
         (take 5)
         (map #(dissoc % :score)))))

(define-routes)
