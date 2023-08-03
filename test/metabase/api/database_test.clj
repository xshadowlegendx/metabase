(ns metabase.api.database-test
  "Tests for /api/database endpoints."
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [medley.core :as m]
   [metabase.api.database :as api.database]
   [metabase.api.table :as api.table]
   [metabase.driver :as driver]
   [metabase.driver.h2 :as h2]
   [metabase.driver.util :as driver.u]
   [metabase.mbql.schema :as mbql.s]
   [metabase.models
    :refer [Card Collection Database Field FieldValues Metric Segment Table]]
   [metabase.models.database :as database :refer [protected-password]]
   [metabase.models.permissions :as perms]
   [metabase.models.permissions-group :as perms-group]
   [metabase.models.setting :as setting :refer [defsetting]]
   [metabase.public-settings.premium-features :as premium-features]
   [metabase.sync.analyze :as analyze]
   [metabase.sync.field-values :as field-values]
   [metabase.sync.sync-metadata :as sync-metadata]
   [metabase.test :as mt]
   [metabase.test.fixtures :as fixtures]
   [metabase.test.util :as tu]
   [metabase.util :as u]
   [metabase.util.cron :as u.cron]
   [metabase.util.i18n :refer [deferred-tru]]
   #_{:clj-kondo/ignore [:deprecated-namespace]}
   [metabase.util.schema :as su]
   [ring.util.codec :as codec]
   [schema.core :as s]
   [toucan2.core :as t2]
   [toucan2.tools.with-temp :as t2.with-temp]))

(use-fixtures :once (fixtures/initialize :db :plugins :test-drivers))

;; HELPER FNS

(driver/register! ::test-driver
  :parent :sql-jdbc
  :abstract? true)

(defmethod driver/connection-properties ::test-driver
  [_]
  nil)

(defmethod driver/can-connect? ::test-driver
  [_ _]
  true)

(defn- db-details
  "Return default column values for a database (either the test database, via `(mt/db)`, or optionally passed in)."
  ([]
   (-> (db-details (mt/db))
       (assoc :initial_sync_status "complete")))

  ([{driver :engine, :as db}]
   (merge
    (mt/object-defaults Database)
    (select-keys db [:created_at :id :details :updated_at :timezone :name :dbms_version])
    {:engine (u/qualified-name (:engine db))
     :features (map u/qualified-name (driver.u/features driver db))
     :initial_sync_status "complete"})))

(defn- table-details [table]
  (-> (merge (mt/obj->json->obj (mt/object-defaults Table))
             (select-keys table [:active :created_at :db_id :description :display_name :entity_type
                                 :id :name :rows :schema :updated_at :visibility_type :initial_sync_status]))
      (update :entity_type #(when % (str "entity/" (name %))))
      (update :visibility_type #(when % (name %)))))

(defn- expected-tables [db-or-id]
  (map table-details (t2/select Table
                       :db_id (u/the-id db-or-id), :active true, :visibility_type nil
                       {:order-by [[:%lower.schema :asc] [:%lower.display_name :asc]]})))

(defn- field-details [field]
  (mt/derecordize
   (merge
    (mt/object-defaults Field)
    {:target nil}
    (select-keys
     field
     [:updated_at :id :created_at :last_analyzed :fingerprint :fingerprint_version :fk_target_field_id :position]))))

(defn- card-with-native-query {:style/indent 1} [card-name & {:as kvs}]
  (merge
   {:name          card-name
    :database_id   (mt/id)
    :dataset_query {:database (mt/id)
                    :type     :native
                    :native   {:query (format "SELECT * FROM VENUES")}}}
   kvs))

(defn- card-with-mbql-query {:style/indent 1} [card-name & {:as inner-query-clauses}]
  {:name          card-name
   :database_id   (mt/id)
   :dataset_query {:database (mt/id)
                   :type     :query
                   :query    inner-query-clauses}})

(defn- virtual-table-for-card [card & {:as kvs}]
  (merge
   {:id               (format "card__%d" (u/the-id card))
    :db_id            (:database_id card)
    :display_name     (:name card)
    :schema           "Everything else"
    :moderated_status nil
    :description      nil}
   kvs))

(defn- ok-mbql-card []
  (assoc (card-with-mbql-query "OK Card"
                               :source-table (mt/id :checkins))
         :result_metadata [{:name "num_toucans"}]))

(deftest get-database-test
  (testing "GET /api/database/:id"
    (testing "DB details visibility"
      (testing "Regular users should not see DB details"
        (is (= (-> (db-details)
                   (dissoc :details :schedules))
               (-> (mt/user-http-request :rasta :get 200 (format "database/%d" (mt/id)))
                   (dissoc :schedules)))))

      (testing "Superusers should see DB details"
        (is (= (assoc (db-details) :can-manage true)
               (-> (mt/user-http-request :crowberto :get 200 (format "database/%d" (mt/id)))
                   (dissoc :schedules))))))

    (mt/with-temp* [Database [db  {:name "My DB", :engine ::test-driver}]
                    Table    [t1  {:name "Table 1", :db_id (:id db)}]
                    Table    [t2  {:name "Table 2", :db_id (:id db)}]
                    Table    [_t3 {:name "Table 3", :db_id (:id db), :visibility_type "hidden"}]
                    Field    [f1  {:name "Field 1.1", :table_id (:id t1)}]
                    Field    [f2  {:name "Field 2.1", :table_id (:id t2)}]
                    Field    [f3  {:name "Field 2.2", :table_id (:id t2)}]]
      (testing "`?include=tables` -- should be able to include Tables"
        (is (= {:tables [(table-details t1)
                         (table-details t2)]}
               (select-keys (mt/user-http-request :lucky :get 200 (format "database/%d?include=tables" (:id db)))
                            [:tables]))))

      (testing "`?include=tables.fields` -- should be able to include Tables and Fields"
        (letfn [(field-details* [field]
                  (assoc (into {} (t2/hydrate field [:target :has_field_values] :has_field_values))
                         :base_type        "type/Text"
                         :visibility_type  "normal"
                         :has_field_values "search"))]
          (is (= {:tables [(assoc (table-details t1) :fields [(field-details* f1)])
                           (assoc (table-details t2) :fields [(field-details* f2)
                                                              (field-details* f3)])]}
                 (select-keys (mt/user-http-request :lucky :get 200 (format "database/%d?include=tables.fields" (:id db)))
                              [:tables]))))))

    (testing "Invalid `?include` should return an error"
      (is (= {:errors {:include "value may be nil, or if non-nil, value must be one of: `tables`, `tables.fields`."}}
             (mt/user-http-request :lucky :get 400 (format "database/%d?include=schemas" (mt/id))))))))

(deftest get-database-usage-info-test
  (mt/with-temp*
    [Database [{db-id :id}]
     Table    [{table-id-1 :id} {:db_id db-id}]
     Table    [{table-id-2 :id} {:db_id db-id}]
     ;; question
     Card     [_                {:database_id db-id
                                 :table_id    table-id-1
                                 :dataset     false}]
     ;; dataset
     Card     [_                {:database_id db-id
                                 :table_id    table-id-1
                                 :dataset     true}]
     Card     [_                {:database_id db-id
                                 :table_id    table-id-2
                                 :dataset     true
                                 :archived    true}]

     Metric   [_                {:table_id table-id-1}]
     Metric   [_                {:table_id table-id-1}]
     Metric   [_                {:table_id table-id-2}]
     Segment  [_                {:table_id table-id-2}]]

    (testing "should require admin"
      (is (= "You don't have permissions to do that."
             (mt/user-http-request :rasta :get 403 (format "database/%d/usage_info" db-id)))))

    (testing "return the correct usage info"
      (is (= {:question 1
              :dataset  2
              :metric   3
              :segment  1}
             (mt/user-http-request :crowberto :get 200 (format "database/%d/usage_info" db-id)))))

    (testing "404 if db does not exist"
      (let [non-existing-db-id (inc (t2/select-one-pk Database {:order-by [[:id :desc]]}))]
        (is (= "Not found."
               (mt/user-http-request :crowberto :get 404
                                     (format "database/%d/usage_info" non-existing-db-id)))))))
  (mt/with-temp*
    [Database [{db-id :id}]]
    (testing "should work with DB that has no tables"
      (is (= {:question 0
              :dataset  0
              :metric   0
              :segment  0}
             (mt/user-http-request :crowberto :get 200 (format "database/%d/usage_info" db-id)))))))

(defn- create-db-via-api! [& [m]]
  (let [db-name (mt/random-name)]
    (try
      (let [{db-id :id, :as response} (with-redefs [driver/available?   (constantly true)
                                                    driver/can-connect? (constantly true)]
                                        (mt/user-http-request :crowberto :post 200 "database"
                                                              (merge
                                                               {:name    db-name
                                                                :engine  (u/qualified-name ::test-driver)
                                                                :details {:db "my_db"}}
                                                               m)))]
        (is (schema= {:id       s/Int
                      s/Keyword s/Any}
                     response))
        (when (integer? db-id)
          (t2/select-one Database :id db-id)))
      (finally (t2/delete! Database :name db-name)))))

(deftest create-db-test
  (testing "POST /api/database"
    (testing "Check that we can create a Database"
      (is (schema= (merge
                    (m/map-vals s/eq (mt/object-defaults Database))
                    {:metadata_sync_schedule #"0 \d{1,2} \* \* \* \? \*"
                     :cache_field_values_schedule #"0 \d{1,2} \d{1,2} \* \* \? \*"}
                    {:created_at java.time.temporal.Temporal
                     :engine     (s/eq ::test-driver)
                     :id         su/IntGreaterThanZero
                     :details    (s/eq {:db "my_db"})
                     :updated_at java.time.temporal.Temporal
                     :name       su/NonBlankString
                     :features   (s/eq (driver.u/features ::test-driver (mt/db)))
                     :creator_id (s/eq (mt/user->id :crowberto))})
                   (create-db-via-api!))))

    (testing "can we set `is_full_sync` to `false` when we create the Database?"
      (is (= {:is_full_sync false}
             (select-keys (create-db-via-api! {:is_full_sync false}) [:is_full_sync]))))

    (testing "if `:let-user-control-scheduling` is false it will ignore any schedules provided"
      (let [monthly-schedule {:schedule_type "monthly" :schedule_day "fri" :schedule_frame "last"}
            {:keys [details metadata_sync_schedule cache_field_values_schedule]}
            (create-db-via-api! {:schedules {:metadata_sync      monthly-schedule
                                             :cache_field_values monthly-schedule}})]
        (is (not (:let-user-control-scheduling details)))
        (is (= "daily" (-> cache_field_values_schedule u.cron/cron-string->schedule-map :schedule_type)))
        (is (= "hourly" (-> metadata_sync_schedule u.cron/cron-string->schedule-map :schedule_type)))))

    (testing "if `:let-user-control-scheduling` is true it will accept the schedules"
      (let [monthly-schedule {:schedule_type "monthly" :schedule_day "fri" :schedule_frame "last"}
            {:keys [details metadata_sync_schedule cache_field_values_schedule]}
            (create-db-via-api! {:details   {:let-user-control-scheduling true}
                                 :schedules {:metadata_sync      monthly-schedule
                                             :cache_field_values monthly-schedule}})]
        (is (:let-user-control-scheduling details))
        (is (= "monthly" (-> cache_field_values_schedule u.cron/cron-string->schedule-map :schedule_type)))
        (is (= "monthly" (-> metadata_sync_schedule u.cron/cron-string->schedule-map :schedule_type)))))

    (testing "well known connection errors are reported properly"
      (let [dbname (mt/random-name)
            exception (Exception. (format "FATAL: database \"%s\" does not exist" dbname))]
        (is (= {:errors {:dbname "check your database name settings"},
                :message "Looks like the Database name is incorrect."}
               (with-redefs [driver/can-connect? (fn [& _] (throw exception))]
                 (mt/user-http-request :crowberto :post 400 "database"
                                       {:name         dbname
                                        :engine       "postgres"
                                        :details      {:host "localhost", :port 5432
                                                       :dbname "fakedb", :user "rastacan"}}))))))

    (testing "unknown connection errors are reported properly"
      (let [exception (Exception. "Unknown driver message" (java.net.ConnectException. "Failed!"))]
        (is (= {:errors  {:host "check your host settings"
                          :port "check your port settings"}
                :message "Hmm, we couldn't connect to the database. Make sure your Host and Port settings are correct"}
               (with-redefs [driver/available?   (constantly true)
                             driver/can-connect? (fn [& _] (throw exception))]
                 (mt/user-http-request :crowberto :post 400 "database"
                                       {:name    (mt/random-name)
                                        :engine  (u/qualified-name ::test-driver)
                                        :details {:db "my_db"}}))))))

    (testing "should throw a 402 error if trying to set `cache_ttl` on OSS"
      (with-redefs [premium-features/enable-cache-granular-controls? (constantly false)]
        (mt/user-http-request :crowberto :post 402 "database"
                              {:name      (mt/random-name)
                               :engine    (u/qualified-name ::test-driver)
                               :details   {:db "my_db"}
                               :cache_ttl 13})))

    (testing "should allow setting `cache_ttl` on EE"
      (with-redefs [premium-features/enable-cache-granular-controls? (constantly true)]
        (is (partial= {:cache_ttl 13}
                      (create-db-via-api! {:cache_ttl 13})))))))

(deftest disallow-creating-h2-database-test
  (testing "POST /api/database/:id"
    (mt/with-model-cleanup [Database]
      (let [db-name (mt/random-name)
            details (:details (mt/db))]
        (is (= {:message "H2 is not supported as a data warehouse"}
               (mt/user-http-request :crowberto :post 400 "database" {:engine :h2, :name db-name, :details details})))
        (is (not (t2/exists? Database :name db-name)))))))

(deftest delete-database-test
  (testing "DELETE /api/database/:id"
    (testing "Check that a superuser can delete a Database"
      (t2.with-temp/with-temp [Database db]
        (mt/user-http-request :crowberto :delete 204 (format "database/%d" (:id db)))
        (is (false? (t2/exists? Database :id (u/the-id db))))))

    (testing "Check that a non-superuser cannot delete a Database"
      (t2.with-temp/with-temp [Database db]
        (mt/user-http-request :rasta :delete 403 (format "database/%d" (:id db)))))))

(defn- api-update-database! [expected-status-code db-or-id changes]
  (with-redefs [h2/*allow-testing-h2-connections* true]
    (mt/user-http-request :crowberto :put expected-status-code (format "database/%d" (u/the-id db-or-id))
                          changes)))

(deftest update-database-test
  (testing "PUT /api/database/:id"
    (testing "Check that we can update fields in a Database"
      (t2.with-temp/with-temp [Database {db-id :id}]
        (let [updates {:name         "Cam's Awesome Toucan Database"
                       :engine       "h2"
                       :is_full_sync false
                       :details      {:host "localhost", :port 5432, :dbname "fakedb", :user "rastacan"}}
              update! (fn [expected-status-code]
                        (api-update-database! expected-status-code db-id updates))]
          (testing "Should check that connection details are valid on save"
            (is (=? {:errors {:db "check your connection string"}}
                    (update! 400))))
          (testing "If connection details are valid, we should be able to update the Database"
            (with-redefs [driver/can-connect? (constantly true)]
              (is (= nil
                     (:valid (update! 200))))
              (let [curr-db (t2/select-one [Database :name :engine :details :is_full_sync], :id db-id)]
                (is (=
                     {:details      {:host "localhost", :port 5432, :dbname "fakedb", :user "rastacan"}
                      :engine       :h2
                      :name         "Cam's Awesome Toucan Database"
                      :is_full_sync false
                      :features     (driver.u/features :h2 curr-db)}
                     (into {} curr-db)))))))))

    (testing "should be able to set `auto_run_queries`"
      (testing "when creating a Database"
        (is (= {:auto_run_queries false}
               (select-keys (create-db-via-api! {:auto_run_queries false}) [:auto_run_queries]))))
      (testing "when updating a Database"
        (t2.with-temp/with-temp [Database {db-id :id} {:engine ::test-driver}]
          (let [updates {:auto_run_queries false}]
            (mt/user-http-request :crowberto :put 200 (format "database/%d" db-id) updates))
          (is (= false
                 (t2/select-one-fn :auto_run_queries Database, :id db-id))))))

    (testing "should not be able to modify `cache_ttl` in OSS"
      (with-redefs [premium-features/enable-cache-granular-controls? (constantly false)]
        (t2.with-temp/with-temp [Database {db-id :id} {:engine ::test-driver}]
          (let [updates {:cache_ttl 13}]
            (mt/user-http-request :crowberto :put 200 (format "database/%d" db-id) updates))
          (is (= nil
                 (t2/select-one-fn :cache_ttl Database, :id db-id))))))

    (testing "should be able to set and unset `cache_ttl` in EE"
      (with-redefs [premium-features/enable-cache-granular-controls? (constantly true)]
        (t2.with-temp/with-temp [Database {db-id :id} {:engine ::test-driver}]
          (let [updates1 {:cache_ttl 1337}
                updates2 {:cache_ttl nil}
                updates1! (fn [] (mt/user-http-request :crowberto :put 200 (format "database/%d" db-id) updates1))
                updates2! (fn [] (mt/user-http-request :crowberto :put 200 (format "database/%d" db-id) updates2))]
            (updates1!)
            (let [curr-db (t2/select-one [Database :cache_ttl], :id db-id)]
              (is (= 1337 (:cache_ttl curr-db))))
            (updates2!)
            (let [curr-db (t2/select-one [Database :cache_ttl], :id db-id)]
              (is (= nil (:cache_ttl curr-db))))))))))

(deftest disallow-updating-h2-database-details-test
  (testing "PUT /api/database/:id"
    (letfn [(update! [db request-body]
              (mt/user-http-request :crowberto :put 400 (str "database/" (u/the-id db)) request-body))]
      (t2.with-temp/with-temp [Database db {:name    (mt/random-name)
                                            :details (:details (mt/db))
                                            :engine  :postgres}]
        (testing "Don't allow changing engine to H2"
          (is (= {:message "H2 is not supported as a data warehouse"}
                 (update! db {:engine :h2})))
          (is (= :postgres
                 (t2/select-one-fn :engine Database (u/the-id db))))))
      (t2.with-temp/with-temp [Database db {:name    (mt/random-name)
                                            :details (:details (mt/db))
                                            :engine  :h2}]
        (testing "Don't allow editing H2 connection details"
          (is (= {:message "H2 is not supported as a data warehouse"}
                 (update! db {:details {:db "mem:test-data;USER=GUEST;PASSWORD=guest;WHATEVER=true"}})))
          (is (= (:details db)
                 (t2/select-one-fn :details Database (u/the-id db)))))))))

(deftest enable-model-actions-with-user-controlled-scheduling-test
  (testing "Should be able to enable/disable actions for a database with user-controlled scheduling (metabase#30699)"
    (t2.with-temp/with-temp [Database {db-id :id} {:details  {:let-user-control-scheduling true}
                                                   :settings {:database-enable-actions true}}]
      (is (false? (get-in (mt/user-http-request :crowberto
                                                :put 200
                                                (format "database/%s" db-id)
                                                {:settings {:database-enable-actions false}})
                          [:settings :database-enable-actions])))
      (is (true? (get-in (mt/user-http-request :crowberto
                                               :put 200
                                               (format "database/%s" db-id)
                                               {:settings {:database-enable-actions true}})
                         [:settings :database-enable-actions]))))))

(deftest fetch-database-metadata-test
  (testing "GET /api/database/:id/metadata"
    (is (= (merge (dissoc (mt/object-defaults Database) :details)
                  (select-keys (mt/db) [:created_at :id :updated_at :timezone :initial_sync_status :dbms_version])
                  {:engine        "h2"
                   :name          "test-data"
                   :features      (map u/qualified-name (driver.u/features :h2 (mt/db)))
                   :tables        [(merge
                                    (mt/obj->json->obj (mt/object-defaults Table))
                                    (t2/select-one [Table :created_at :updated_at] :id (mt/id :categories))
                                    {:schema              "PUBLIC"
                                     :name                "CATEGORIES"
                                     :display_name        "Categories"
                                     :entity_type         "entity/GenericTable"
                                     :initial_sync_status "complete"
                                     :fields              [(merge
                                                            (field-details (t2/select-one Field :id (mt/id :categories :id)))
                                                            {:table_id          (mt/id :categories)
                                                             :semantic_type     "type/PK"
                                                             :name              "ID"
                                                             :display_name      "ID"
                                                             :database_type     "BIGINT"
                                                             :base_type         "type/BigInteger"
                                                             :effective_type    "type/BigInteger"
                                                             :visibility_type   "normal"
                                                             :has_field_values  "none"
                                                             :database_position 0
                                                             :database_required false
                                                             :database_is_auto_increment true})
                                                           (merge
                                                            (field-details (t2/select-one Field :id (mt/id :categories :name)))
                                                            {:table_id          (mt/id :categories)
                                                             :semantic_type     "type/Name"
                                                             :name              "NAME"
                                                             :display_name      "Name"
                                                             :database_type     "CHARACTER VARYING"
                                                             :base_type         "type/Text"
                                                             :effective_type    "type/Text"
                                                             :visibility_type   "normal"
                                                             :has_field_values  "list"
                                                             :database_position 1
                                                             :database_required true
                                                             :database_is_auto_increment false})]
                                     :segments     []
                                     :metrics      []
                                     :id           (mt/id :categories)
                                     :db_id        (mt/id)})]})
           (let [resp (mt/derecordize (mt/user-http-request :rasta :get 200 (format "database/%d/metadata" (mt/id))))]
             (assoc resp :tables (filter #(= "CATEGORIES" (:name %)) (:tables resp))))))))

(deftest fetch-database-fields-test
  (letfn [(f [fields] (m/index-by #(str (:table_name %) "." (:name %)) fields))]
    (testing "GET /api/database/:id/fields"
      (is (partial= {"VENUES.ID"        {:name "ID" :display_name "ID"
                                         :table_name "VENUES"}
                     "CHECKINS.USER_ID" {:name "USER_ID" :display_name "User ID"
                                         :table_name "CHECKINS"}}
                    (f (mt/user-http-request :rasta :get 200 (format "database/%d/fields" (mt/id))))))
      (testing "shows display names"
        (mt/with-temp* [Table [{t-id :id} {:name "FOO_TABLE" :display_name "irrelevant"
                                           :db_id (mt/id)}]
                        Field [_ {:name "F_NAME" :display_name "user editable"
                                  :table_id t-id}]]
          (is (partial= {"FOO_TABLE.F_NAME" {:name "F_NAME" :display_name "user editable"
                                             :table_name "FOO_TABLE"}}
                        (f (mt/user-http-request :rasta :get 200 (format "database/%d/fields" (mt/id)))))))))))

(deftest fetch-database-metadata-include-hidden-test
  ;; NOTE: test for the exclude_uneditable parameter lives in metabase-enterprise.advanced-permissions.common-test
  (mt/with-temp-vals-in-db Table (mt/id :categories) {:visibility_type "hidden"}
    (mt/with-temp-vals-in-db Field (mt/id :venues :price) {:visibility_type "sensitive"}
      (testing "GET /api/database/:id/metadata?include_hidden=true"
        (let [tables (->> (mt/user-http-request :rasta :get 200 (format "database/%d/metadata?include_hidden=true" (mt/id)))
                          :tables)]
          (is (some (partial = "CATEGORIES") (map :name tables)))
          (is (->> tables
                   (filter #(= "VENUES" (:name %)))
                   first
                   :fields
                   (map :name)
                   (some (partial = "PRICE"))))))
      (testing "GET /api/database/:id/metadata"
        (let [tables (->> (mt/user-http-request :rasta :get 200 (format "database/%d/metadata" (mt/id)))
                          :tables)]
          (is (not (some (partial = "CATEGORIES") (map :name tables))))
          (is (not (->> tables
                        (filter #(= "VENUES" (:name %)))
                        first
                        :fields
                        (map :name)
                        (some (partial = "PRICE"))))))))))

(deftest fetch-database-metadata-remove-inactive-test
  (mt/with-temp* [Database [{db-id :id}]
                  Table    [_ {:db_id db-id, :active false}]]
    (testing "GET /api/database/:id/metadata?include_hidden=true"
        (let [tables (->> (mt/user-http-request :rasta :get 200 (format "database/%d/metadata?remove_inactive=true" db-id))
                          :tables)]
          (is (= () tables))))))

(deftest autocomplete-suggestions-test
  (let [prefix-fn (fn [db-id prefix]
                    (mt/user-http-request :rasta :get 200
                                          (format "database/%d/autocomplete_suggestions" db-id)
                                          :prefix prefix))
        substring-fn (fn [db-id search]
                       (mt/user-http-request :rasta :get 200
                                             (format "database/%d/autocomplete_suggestions" db-id)
                                             :substring search))]
    (testing "GET /api/database/:id/autocomplete_suggestions"
      (doseq [[prefix expected] {"u"   [["USERS" "Table"]
                                        ["USER_ID" "CHECKINS :type/Integer :type/FK"]]
                                 "c"   [["CATEGORIES" "Table"]
                                        ["CHECKINS" "Table"]
                                        ["CATEGORY_ID" "VENUES :type/Integer :type/FK"]]
                                 "cat" [["CATEGORIES" "Table"]
                                        ["CATEGORY_ID" "VENUES :type/Integer :type/FK"]]}]
        (is (= expected (prefix-fn (mt/id) prefix))))
      (testing " handles large numbers of tables and fields sensibly with prefix"
        (mt/with-model-cleanup [Field Table Database]
          (let [tmp-db (first (t2/insert-returning-instances! Database {:name "Temp Autocomplete Pagination DB" :engine "h2" :details "{}"}))]
            ;; insert more than 50 temporary tables and fields
            (doseq [i (range 60)]
              (let [tmp-tbl (first (t2/insert-returning-instances! Table {:name (format "My Table %d" i) :db_id (u/the-id tmp-db) :active true}))]
                (t2/insert! Field {:name (format "My Field %d" i) :table_id (u/the-id tmp-tbl) :base_type "type/Text" :database_type "varchar"})))
            ;; for each type-specific prefix, we should get 50 fields
            (is (= 50 (count (prefix-fn (u/the-id tmp-db) "My Field"))))
            (is (= 50 (count (prefix-fn (u/the-id tmp-db) "My Table"))))
            (let [my-results (prefix-fn (u/the-id tmp-db) "My")]
              ;; for this prefix, we should a mixture of 25 fields and 25 tables
              (is (= 50 (count my-results)))
              (is (= 25 (-> (filter #(str/starts-with? % "My Field") (map first my-results))
                            count)))
              (is (= 25 (-> (filter #(str/starts-with? % "My Table") (map first my-results))
                            count))))
            (testing " behaves differently with search and prefix query params"
              (is (= 0 (count (prefix-fn (u/the-id tmp-db) "a"))))
              (is (= 50 (count (substring-fn (u/the-id tmp-db) "a"))))
              ;; setting both uses search:
              (is (= 50 (count (mt/user-http-request :rasta :get 200
                                                     (format "database/%d/autocomplete_suggestions" (u/the-id tmp-db))
                                                     :prefix "a"
                                                     :substring "a")))))))))))

(deftest card-autocomplete-suggestions-test
  (testing "GET /api/database/:id/card_autocomplete_suggestions"
    (mt/with-temp* [Collection [collection {:name "Maz Analytics"}]
                    Card       [card-1 (card-with-native-query "Maz Quote Views Per Month" :collection_id (:id collection))]
                    Card       [card-2 (card-with-native-query "Maz Quote Views Per Day" :dataset true)]
                    Card       [card-3 (card-with-native-query "Maz Quote Views Per Day")]]
      (let [card->result {card-1 (assoc (select-keys card-1 [:id :name :dataset]) :collection_name (:name collection))
                          card-2 (assoc (select-keys card-2 [:id :name :dataset]) :collection_name nil)
                          card-3 (assoc (select-keys card-3 [:id :name :dataset]) :collection_name nil)}]
        (testing "exclude cards without perms"
          (mt/with-non-admin-groups-no-root-collection-perms
            (is (= [(card->result card-1)]
                   (mt/user-http-request :rasta :get 200
                                         (format "database/%d/card_autocomplete_suggestions" (mt/id))
                                         :query "maz"))))
          (testing "cards should match the query"
            (doseq [[query expected-cards] [; in all these queries, card-2 should be first because it's a model,
                                            ; followed by card-3 because it's created more recently than card-1
                                            ["QUOTE-views" [card-2 card-3 card-1]]
                                            ["per-day" [card-2 card-3]]
                                            [(str (:id card-1)) [card-1]]
                                            [(str (:id card-2) "-maz") [card-2]]
                                            [(str (:id card-2) "-kyle") []]]]
              (testing (format "query = %s" query)
                (is (= (map card->result expected-cards)
                       (mt/user-http-request :rasta :get 200
                                             (format "database/%d/card_autocomplete_suggestions" (mt/id))
                                             :query query))))))))
      (testing "should reject requests for databases for which the user has no perms"
        (mt/with-temp* [Database [{database-id :id}]
                        Card     [_ (card-with-native-query "Maz Quote Views Per Month" :database_id database-id)]]
          (perms/revoke-data-perms! (perms-group/all-users) database-id)
          (is (= "You don't have permissions to do that."
                 (mt/user-http-request :rasta :get 403
                                       (format "database/%d/card_autocomplete_suggestions" database-id)
                                       :query "maz"))))))))

(driver/register! ::no-nested-query-support
                  :parent :sql-jdbc
                  :abstract? true)

(defmethod driver/database-supports? [::no-nested-query-support :nested-queries] [_driver _feature _db] false)

(defn- get-all
  ([endpoint existing-ids]
   (get-all :rasta endpoint existing-ids))
  ([user endpoint existing-ids]
   (let [new?        (complement (set existing-ids))
         dbs         (->> (mt/user-http-request user :get 200 endpoint)
                          :data
                          (filter (comp new? :id)))]
     {:data  dbs
      :total (count dbs)})))

(deftest databases-list-test
  (testing "GET /api/database"
    (testing "Test that we can get all the DBs (ordered by name, then driver)"
      (testing "Database details/settings *should not* come back for Rasta since she's not a superuser"
        (let [expected-keys (-> (into #{:features :native_permissions} (keys (t2/select-one Database :id (mt/id))))
                                (disj :details))]
          (doseq [db (:data (mt/user-http-request :rasta :get 200 "database"))]
            (testing (format "Database %s %d %s" (:engine db) (u/the-id db) (pr-str (:name db)))
              (is (= expected-keys
                     (set (keys db))))))))
      (testing "Make sure databases don't paginate"
        (mt/with-temp* [Database [_ {:engine ::test-driver}]
                        Database [_ {:engine ::test-driver}]
                        Database [_ {:engine ::test-driver}]]
          (is (< 1 (count (:data (mt/user-http-request :rasta :get 200 "database" :limit 1 :offset 0))))))))


    (testing "`?include=tables`"
      (let [old-ids (t2/select-pks-set Database)]
        (t2.with-temp/with-temp [Database _ {:engine (u/qualified-name ::test-driver)}]
          (doseq [db (:data (get-all "database?include=tables" old-ids))]
            (testing (format "Database %s %d %s" (:engine db) (u/the-id db) (pr-str (:name db)))
              (is (= (expected-tables db)
                     (:tables db))))))))
    (testing "`?include_only_uploadable=true` -- excludes drivers that don't support uploads"
      (let [old-ids (t2/select-pks-set Database)]
        (t2.with-temp/with-temp [Database _ {:engine ::test-driver}]
          (is (= {:data  []
                  :total 0}
                 (get-all "database?include_only_uploadable=true" old-ids))))))
    (testing "`?include_only_uploadable=true` -- includes drivers that do support uploads"
      (let [old-ids (t2/select-pks-set Database)]
        (t2.with-temp/with-temp [Database _ {:engine :postgres :name "The Chosen One"}]
          (testing "Must be an admin"
            (let [result (get-all :crowberto "database?include_only_uploadable=true" old-ids)]
              (is (= 1 (:total result)))
              (is (= "The Chosen One" (-> result :data first :name)))))
          (testing "No results for non-admins"
            (is (= {:data []
                    :total 0}
                   (get-all :rasta "database?include_only_uploadable=true" old-ids)))))))))

(deftest databases-list-include-saved-questions-test
  (testing "GET /api/database?saved=true"
    (t2.with-temp/with-temp [Card _ (assoc (card-with-native-query "Some Card")
                                           :result_metadata [{:name "col_name"}])]
      (testing "We should be able to include the saved questions virtual DB (without Tables) with the param ?saved=true"
        (is (= {:name               "Saved Questions"
                :id                 mbql.s/saved-questions-virtual-database-id
                :features           ["basic-aggregations"]
                :is_saved_questions true}
               (last (:data (mt/user-http-request :lucky :get 200 "database?saved=true")))))))

    (testing "We should not include the saved questions virtual DB if there aren't any cards"
      (is (not-any?
           :is_saved_questions
           (mt/user-http-request :lucky :get 200 "database?saved=true"))))
    (testing "Omit virtual DB if nested queries are disabled"
      (tu/with-temporary-setting-values [enable-nested-queries false]
        (is (every? some? (:data (mt/user-http-request :lucky :get 200 "database?saved=true"))))))))

(deftest fetch-databases-with-invalid-driver-test
  (testing "GET /api/database"
    (testing "\nEndpoint should still work even if there is a Database saved with a invalid driver"
      (t2.with-temp/with-temp [Database {db-id :id} {:engine "my-invalid-driver"}]
        (testing (format "\nID of Database with invalid driver = %d" db-id)
          (doseq [params [nil
                          "?saved=true"
                          "?include=tables"]]
            (testing (format "\nparams = %s" (pr-str params))
              (let [db-ids (set (map :id (:data (mt/user-http-request :lucky :get 200 (str "database" params)))))]
                (testing "DB should still come back, even though driver is invalid :shrug:"
                  (is (contains? db-ids db-id)))))))))))

(def ^:private SavedQuestionsDB
  "Schema for the expected shape of info about the 'saved questions' virtual DB from API responses."
  {:name               (s/eq "Saved Questions")
   :id                 (s/eq -1337)
   :features           (s/eq ["basic-aggregations"])
   :is_saved_questions (s/eq true)
   :tables             [{:id               #"^card__\d+$"
                         :db_id            s/Int
                         :display_name     s/Str
                         :moderated_status (s/enum nil "verified")
                         :schema           s/Str ; collection name
                         :description      (s/maybe s/Str)}]})

(defn- check-tables-included [response & tables]
  (let [response-tables (set (:tables response))]
    (doseq [table tables]
      (testing (format "Should include Table %s" (pr-str table))
        (is (contains? response-tables table))))))

(defn- check-tables-not-included [response & tables]
  (let [response-tables (set (:tables response))]
    (doseq [table tables]
      (testing (format "Should *not* include Table %s" (pr-str table))
        (is (not (contains? response-tables table)))))))

(deftest databases-list-include-saved-questions-tables-test
  (testing "GET /api/database?saved=true&include=tables"
    (letfn [(fetch-virtual-database []
              (some #(when (= (:name %) "Saved Questions")
                       %)
                    (:data (mt/user-http-request :crowberto :get 200 "database?saved=true&include=tables"))))]
      (testing "Check that we get back 'virtual' tables for Saved Questions"
        (testing "The saved questions virtual DB should be the last DB in the list"
          (t2.with-temp/with-temp [Card card (card-with-native-query "Maz Quote Views Per Month")]
            ;; run the Card which will populate its result_metadata column
            (mt/user-http-request :crowberto :post 202 (format "card/%d/query" (u/the-id card)))
            ;; Now fetch the database list. The 'Saved Questions' DB should be last on the list
            (let [response (last (:data (mt/user-http-request :crowberto :get 200 "database?saved=true&include=tables")))]
              (is (schema= SavedQuestionsDB
                           response))
              (check-tables-included response (virtual-table-for-card card)))))

        (testing "Make sure saved questions are NOT included if the setting is disabled"
          (t2.with-temp/with-temp [Card card (card-with-native-query "Maz Quote Views Per Month")]
            (mt/with-temporary-setting-values [enable-nested-queries false]
              ;; run the Card which will populate its result_metadata column
              (mt/user-http-request :crowberto :post 202 (format "card/%d/query" (u/the-id card)))
              ;; Now fetch the database list. The 'Saved Questions' DB should NOT be in the list
              (is (= nil
                     (fetch-virtual-database)))))))

      (testing "should pretend Collections are schemas"
        (mt/with-temp* [Collection [stamp-collection {:name "Stamps"}]
                        Collection [coin-collection  {:name "Coins"}]
                        Card       [stamp-card (card-with-native-query "Total Stamp Count", :collection_id (u/the-id stamp-collection))]
                        Card       [coin-card  (card-with-native-query "Total Coin Count",  :collection_id (u/the-id coin-collection))]]
          ;; run the Cards which will populate their result_metadata columns
          (doseq [card [stamp-card coin-card]]
            (mt/user-http-request :crowberto :post 202 (format "card/%d/query" (u/the-id card))))
          ;; Now fetch the database list. The 'Saved Questions' DB should be last on the list. Cards should have their
          ;; Collection name as their Schema
          (let [response (last (:data (mt/user-http-request :crowberto :get 200 "database?saved=true&include=tables")))]
            (is (schema= SavedQuestionsDB
                         response))
            (check-tables-included
             response
             (virtual-table-for-card coin-card :schema "Coins")
             (virtual-table-for-card stamp-card :schema "Stamps")))))

      (testing "should remove Cards that have ambiguous columns"
        (mt/with-temp* [Card [ok-card         (assoc (card-with-native-query "OK Card")         :result_metadata [{:name "cam"}])]
                        Card [cambiguous-card (assoc (card-with-native-query "Cambiguous Card") :result_metadata [{:name "cam"} {:name "cam_2"}])]]
          (let [response (fetch-virtual-database)]
            (is (schema= SavedQuestionsDB
                         response))
            (check-tables-included response (virtual-table-for-card ok-card))
            (check-tables-not-included response (virtual-table-for-card cambiguous-card)))))

      (testing "should remove Cards that belong to a driver that doesn't support nested queries"
        (mt/with-temp* [Database [bad-db   {:engine ::no-nested-query-support, :details {}}]
                        Card     [bad-card {:name            "Bad Card"
                                            :dataset_query   {:database (u/the-id bad-db)
                                                              :type     :native
                                                              :native   {:query "[QUERY GOES HERE]"}}
                                            :result_metadata [{:name "sparrows"}]
                                            :database_id     (u/the-id bad-db)}]
                        Card     [ok-card  (assoc (card-with-native-query "OK Card")
                                                  :result_metadata [{:name "finches"}])]]
          (let [response (fetch-virtual-database)]
            (is (schema= SavedQuestionsDB
                         response))
            (check-tables-included response (virtual-table-for-card ok-card))
            (check-tables-not-included response (virtual-table-for-card bad-card)))))

      (testing "should work when there are no DBs that support nested queries"
        (with-redefs [metabase.driver/database-supports? (constantly false)]
          (is (nil? (fetch-virtual-database)))))

      (testing "should work when there are no DBs that support nested queries"
        (with-redefs [metabase.driver/database-supports? (constantly false)]
          (is (nil? (fetch-virtual-database)))))

      (testing "should remove Cards that use cumulative-sum and cumulative-count aggregations"
        (mt/with-temp* [Card [ok-card  (ok-mbql-card)]
                        Card [bad-card (merge
                                        (mt/$ids checkins
                                          (card-with-mbql-query "Cum Count Card"
                                            :source-table $$checkins
                                            :aggregation  [[:cum-count]]
                                            :breakout     [!month.date]))
                                        {:result_metadata [{:name "num_toucans"}]})]]
          (let [response (fetch-virtual-database)]
            (is (schema= SavedQuestionsDB
                         response))
            (check-tables-included response (virtual-table-for-card ok-card))
            (check-tables-not-included response (virtual-table-for-card bad-card))))))))

(deftest db-metadata-saved-questions-db-test
  (testing "GET /api/database/:id/metadata works for the Saved Questions 'virtual' database"
    (t2.with-temp/with-temp [Card card (assoc (card-with-native-query "Birthday Card")
                                              :result_metadata [{:name "age_in_bird_years"}])]
      (let [response (mt/user-http-request :crowberto :get 200
                                           (format "database/%d/metadata" mbql.s/saved-questions-virtual-database-id))]
        (is (schema= {:name               (s/eq "Saved Questions")
                      :id                 (s/eq -1337)
                      :is_saved_questions (s/eq true)
                      :features           (s/eq ["basic-aggregations"])
                      :tables             [{:id               #"^card__\d+$"
                                            :db_id            s/Int
                                            :display_name     s/Str
                                            :moderated_status (s/enum nil "verified")
                                            :schema           s/Str ; collection name
                                            :description      (s/maybe s/Str)
                                            :fields           [su/Map]}]}
                     response))
        (check-tables-included
         response
         (assoc (virtual-table-for-card card)
                :fields [{:name                     "age_in_bird_years"
                          :table_id                 (str "card__" (u/the-id card))
                          :id                       ["field" "age_in_bird_years" {:base-type "type/*"}]
                          :semantic_type            nil
                          :base_type                nil
                          :default_dimension_option nil
                          :dimension_options        []}]))))

    (testing "\nif no eligible Saved Questions exist the endpoint should return empty tables"
      (with-redefs [api.database/cards-virtual-tables (constantly [])]
        (is (= {:name               "Saved Questions"
                :id                 mbql.s/saved-questions-virtual-database-id
                :features           ["basic-aggregations"]
                :is_saved_questions true
                :tables             []}
               (mt/user-http-request :crowberto :get 200
                                     (format "database/%d/metadata" mbql.s/saved-questions-virtual-database-id))))))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                CRON SCHEDULES!                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private schedule-map-for-last-friday-at-11pm
  {:schedule_minute 0
   :schedule_day    "fri"
   :schedule_frame  "last"
   :schedule_hour   23
   :schedule_type   "monthly"})

(def ^:private schedule-map-for-hourly
  {:schedule_minute 0
   :schedule_day    nil
   :schedule_frame  nil
   :schedule_hour   nil
   :schedule_type   "hourly"})

(deftest create-new-db-with-custom-schedules-test
  (testing "Can we create a NEW database and give it custom schedules?"
    (let [db-name (mt/random-name)]
      (try (let [db (with-redefs [driver/available? (constantly true)]
                      (mt/user-http-request :crowberto :post 200 "database"
                                            {:name      db-name
                                             :engine    (u/qualified-name ::test-driver)
                                             :details   {:db "my_db" :let-user-control-scheduling true}
                                             :schedules {:cache_field_values schedule-map-for-last-friday-at-11pm
                                                         :metadata_sync      schedule-map-for-hourly}}))]
             (is (= {:cache_field_values_schedule "0 0 23 ? * 6L *"
                     :metadata_sync_schedule      "0 0 * * * ? *"}
                    (into {} (t2/select-one [Database :cache_field_values_schedule :metadata_sync_schedule] :id (u/the-id db))))))
           (finally (t2/delete! Database :name db-name))))))

(deftest update-schedules-for-existing-db
  (let [attempted {:cache_field_values schedule-map-for-last-friday-at-11pm
                   :metadata_sync      schedule-map-for-hourly}
        expected  {:cache_field_values_schedule "0 0 23 ? * 6L *"
                   :metadata_sync_schedule      "0 0 * * * ? *"}]
    (testing "Can we UPDATE the schedules for an existing database?"
      (testing "We cannot if we don't mark `:let-user-control-scheduling`"
        (t2.with-temp/with-temp [Database db {:engine "h2", :details (:details (mt/db))}]
          (is (=? (select-keys (mt/db) [:cache_field_values_schedule :metadata_sync_schedule])
                  (api-update-database! 200 db (assoc db :schedules attempted))))
          (is (not= expected
                    (into {} (t2/select-one [Database :cache_field_values_schedule :metadata_sync_schedule] :id (u/the-id db)))))))
      (testing "We can if we mark `:let-user-control-scheduling`"
        (t2.with-temp/with-temp [Database db {:engine "h2", :details (:details (mt/db))}]
          (is (=? expected
                  (api-update-database! 200 db
                                        (-> (into {} db)
                                            (assoc :schedules attempted)
                                            (assoc-in [:details :let-user-control-scheduling] true)))))
          (is (= expected
                 (into {} (t2/select-one [Database :cache_field_values_schedule :metadata_sync_schedule] :id (u/the-id db)))))))
      (testing "if we update back to metabase managed schedules it randomizes for us"
        (let [original-custom-schedules expected]
          (t2.with-temp/with-temp [Database db (merge {:engine "h2" :details (assoc (:details (mt/db))
                                                                                    :let-user-control-scheduling true)}
                                                      original-custom-schedules)]
            (is (=? {:id (u/the-id db)}
                    (api-update-database! 200 db
                                          (assoc-in db [:details :let-user-control-scheduling] false))))
            (let [schedules (into {} (t2/select-one [Database :cache_field_values_schedule :metadata_sync_schedule] :id (u/the-id db)))]
              (is (not= original-custom-schedules schedules))
              (is (= "hourly" (-> schedules :metadata_sync_schedule u.cron/cron-string->schedule-map :schedule_type)))
              (is (= "daily" (-> schedules :cache_field_values_schedule u.cron/cron-string->schedule-map :schedule_type))))))))))

(deftest fetch-db-with-expanded-schedules
  (testing "If we FETCH a database will it have the correct 'expanded' schedules?"
    (t2.with-temp/with-temp [Database db {:metadata_sync_schedule      "0 0 * * * ? *"
                                          :cache_field_values_schedule "0 0 23 ? * 6L *"}]
      (is (= {:cache_field_values_schedule "0 0 23 ? * 6L *"
              :metadata_sync_schedule      "0 0 * * * ? *"
              :schedules                   {:cache_field_values schedule-map-for-last-friday-at-11pm
                                            :metadata_sync      schedule-map-for-hourly}}
             (-> (mt/user-http-request :crowberto :get 200 (format "database/%d" (u/the-id db)))
                 (select-keys [:cache_field_values_schedule :metadata_sync_schedule :schedules])))))))

;; Five minutes
(def ^:private long-timeout (* 5 60 1000))

(defn- deliver-when-db [promise-to-deliver expected-db]
  (fn [db]
    (when (= (u/the-id db) (u/the-id expected-db))
      (deliver promise-to-deliver true))))

(deftest trigger-metadata-sync-for-db-test
  (testing "Can we trigger a metadata sync for a DB?"
    (let [sync-called?    (promise)
          analyze-called? (promise)]
      (t2.with-temp/with-temp [Database db {:engine "h2", :details (:details (mt/db))}]
        (with-redefs [sync-metadata/sync-db-metadata! (deliver-when-db sync-called? db)
                      analyze/analyze-db!             (deliver-when-db analyze-called? db)]
          (mt/user-http-request :crowberto :post 200 (format "database/%d/sync_schema" (u/the-id db)))
          ;; Block waiting for the promises from sync and analyze to be delivered. Should be delivered instantly,
          ;; however if something went wrong, don't hang forever, eventually timeout and fail
          (testing "sync called?"
            (is (= true
                   (deref sync-called? long-timeout :sync-never-called))))
          (testing "analyze called?"
            (is (= true
                   (deref analyze-called? long-timeout :analyze-never-called)))))))))

(deftest dismiss-spinner-test
  (testing "Can we dismiss the spinner? (#20863)"
    (t2.with-temp/with-temp [Database db    {:engine "h2", :details (:details (mt/db)) :initial_sync_status "incomplete"}
                             Table    table {:db_id (u/the-id db) :initial_sync_status "incomplete"}]
      (mt/user-http-request :crowberto :post 200 (format "database/%d/dismiss_spinner" (u/the-id db)))
      (testing "dismissed db spinner"
        (is (= "complete" (t2/select-one-fn :initial_sync_status :model/Database (:id db)))))
      (testing "dismissed table spinner"
        (is (= "complete" (t2/select-one-fn :initial_sync_status :model/Table (:id table)))))))

  (testing "can we dissmiss the spinner if db has no tables? (#30837)"
    (t2.with-temp/with-temp [Database db    {:engine "h2", :details (:details (mt/db)) :initial_sync_status "incomplete"}]
      (mt/user-http-request :crowberto :post 200 (format "database/%d/dismiss_spinner" (u/the-id db)))
      (testing "dismissed db spinner"
        (is (= "complete" (t2/select-one-fn :initial_sync_status :model/Database (:id db))))))))

(deftest non-admins-cant-trigger-sync
  (testing "Non-admins should not be allowed to trigger sync"
    (is (= "You don't have permissions to do that."
           (mt/user-http-request :rasta :post 403 (format "database/%d/sync_schema" (mt/id)))))))

(deftest can-rescan-fieldvalues-for-a-db
  (testing "Can we RESCAN all the FieldValues for a DB?"
    (let [update-field-values-called? (promise)]
      (t2.with-temp/with-temp [Database db {:engine "h2", :details (:details (mt/db))}]
        (with-redefs [field-values/update-field-values! (fn [synced-db]
                                                          (when (= (u/the-id synced-db) (u/the-id db))
                                                            (deliver update-field-values-called? :sync-called)))]
          (mt/user-http-request :crowberto :post 200 (format "database/%d/rescan_values" (u/the-id db)))
          (is (= :sync-called
                 (deref update-field-values-called? long-timeout :sync-never-called))))))))

(deftest nonadmins-cant-trigger-rescan
  (testing "Non-admins should not be allowed to trigger re-scan"
    (is (= "You don't have permissions to do that."
           (mt/user-http-request :rasta :post 403 (format "database/%d/rescan_values" (mt/id)))))))

(deftest discard-db-fieldvalues
  (testing "Can we DISCARD all the FieldValues for a DB?"
    (mt/with-temp* [Database    [db       {:engine "h2", :details (:details (mt/db))}]
                    Table       [table-1  {:db_id (u/the-id db)}]
                    Table       [table-2  {:db_id (u/the-id db)}]
                    Field       [field-1  {:table_id (u/the-id table-1)}]
                    Field       [field-2  {:table_id (u/the-id table-2)}]
                    FieldValues [values-1 {:field_id (u/the-id field-1), :values [1 2 3 4]}]
                    FieldValues [values-2 {:field_id (u/the-id field-2), :values [1 2 3 4]}]]
      (is (= {:status "ok"}
             (mt/user-http-request :crowberto :post 200 (format "database/%d/discard_values" (u/the-id db)))))
      (testing "values-1 still exists?"
        (is (= false
               (t2/exists? FieldValues :id (u/the-id values-1)))))
      (testing "values-2 still exists?"
        (is (= false
               (t2/exists? FieldValues :id (u/the-id values-2))))))))

(deftest nonadmins-cant-discard-all-fieldvalues
  (testing "Non-admins should not be allowed to discard all FieldValues"
    (is (= "You don't have permissions to do that."
           (mt/user-http-request :rasta :post 403 (format "database/%d/discard_values" (mt/id)))))))

(defn- api-validate-database
  ([request-body]
   (api-validate-database nil request-body))

  ([{:keys [expected-status-code user]
     :or   {expected-status-code 200
            user                 :crowberto}}
    request-body]
   (with-redefs [h2/*allow-testing-h2-connections* true]
     (mt/user-http-request user :post expected-status-code "database/validate" request-body))))

(defn- test-connection-details [engine details]
  (with-redefs [h2/*allow-testing-h2-connections* true]
    (#'api.database/test-connection-details engine details)))

(deftest validate-database-test
  (testing "POST /api/database/validate"
    (testing "Should require superuser permissions"
      (is (= "You don't have permissions to do that."
             (api-validate-database {:user :rasta, :expected-status-code 403}
                                    {:details {:engine :h2, :details (:details (mt/db))}}))))

    (testing "Underlying `test-connection-details` function should work"
      (is (= (:details (mt/db))
             (test-connection-details "h2" (:details (mt/db))))))

    (testing "Valid database connection details"
      (is (= {:valid true}
             (api-validate-database {:details {:engine :h2, :details (:details (mt/db))}}))))

    (testing "invalid database connection details"
      (testing "calling test-connection-details directly"
        (is (= {:errors  {:db "check your connection string"}
                :message "Implicitly relative file paths are not allowed."
                :valid   false}
               (test-connection-details "h2" {:db "ABC"}))))

      (testing "via the API endpoint"
        (is (= {:valid false}
               (api-validate-database {:details {:engine :h2, :details {:db "ABC"}}})))))

    (let [call-count (atom 0)
          ssl-values (atom [])
          valid?     (atom false)]
      (with-redefs [api.database/test-database-connection (fn [_ details & _]
                                                            (swap! call-count inc)
                                                            (swap! ssl-values conj (:ssl details))
                                                            (if @valid? nil {:valid false}))]
        (testing "with SSL enabled, do not allow non-SSL connections"
          (#'api.database/test-connection-details "postgres" {:ssl true})
          (is (= 1 @call-count))
          (is (= [true] @ssl-values)))

        (reset! call-count 0)
        (reset! ssl-values [])

        (testing "with SSL disabled, try twice (once with, once without SSL)"
          (#'api.database/test-connection-details "postgres" {:ssl false})
          (is (= 2 @call-count))
          (is (= [true false] @ssl-values)))

        (reset! call-count 0)
        (reset! ssl-values [])

        (testing "with SSL unspecified, try twice (once with, once without SSL)"
          (#'api.database/test-connection-details "postgres" {})
          (is (= 2 @call-count))
          (is (= [true nil] @ssl-values)))

        (reset! call-count 0)
        (reset! ssl-values [])
        (reset! valid? true)

        (testing "with SSL disabled, but working try once (since SSL work we don't try without SSL)"
          (is (= {:ssl true}
                 (#'api.database/test-connection-details "postgres" {:ssl false})))
          (is (= 1 @call-count))
          (is (= [true] @ssl-values)))))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                      GET /api/database/:id/schemas & GET /api/database/:id/schema/:schema                      |
;;; +----------------------------------------------------------------------------------------------------------------+

(deftest get-schemas-test
  (testing "GET /api/database/:id/schemas"
    (testing "Multiple schemas are ordered by name"
      (mt/with-temp* [Database [{db-id :id}]
                      Table    [_ {:db_id db-id, :schema "schema3"}]
                      Table    [_ {:db_id db-id, :schema "schema2"}]
                      Table    [_ {:db_id db-id, :schema "schema1"}]]
        (is (= ["schema1" "schema2" "schema3"]
               (mt/user-http-request :rasta :get 200 (format "database/%d/schemas" db-id))))))

    (testing "Looking for a database that doesn't exist should return a 404"
      (is (= "Not found."
             (mt/user-http-request :crowberto :get 404 (format "database/%s/schemas" Integer/MAX_VALUE)))))

    (testing "should work for the saved questions 'virtual' database"
      (mt/with-temp* [Collection [coll   {:name "My Collection"}]
                      Card       [card-1 (assoc (card-with-native-query "Card 1") :collection_id (:id coll))]
                      Card       [card-2 (card-with-native-query "Card 2")]]
        ;; run the cards to populate their result_metadata columns
        (doseq [card [card-1 card-2]]
          (mt/user-http-request :crowberto :post 202 (format "card/%d/query" (u/the-id card))))
        (let [schemas (set (mt/user-http-request :lucky :get 200 (format "database/%d/schemas" mbql.s/saved-questions-virtual-database-id)))]
          (is (contains? schemas "Everything else"))
          (is (contains? schemas "My Collection")))))
    (testing "null and empty schemas should both come back as blank strings"
      (mt/with-temp* [Database [{db-id :id}]
                      Table    [_ {:db_id db-id, :schema ""}]
                      Table    [_ {:db_id db-id, :schema nil}]
                      Table    [_ {:db_id db-id, :schema " "}]]
        (is (= ["" " "]
               (mt/user-http-request :lucky :get 200 (format "database/%d/schemas" db-id))))))))

(deftest get-syncable-schemas-test
  (testing "GET /api/database/:id/syncable_schemas"
    (testing "Multiple schemas are ordered by name"
      ;; We need to redef driver/syncable-schemas here because different databases might have different schemas
      (with-redefs [driver/syncable-schemas (constantly #{"PUBLIC"})]
        (is (= ["PUBLIC"]
               (mt/user-http-request :crowberto :get 200 (format "database/%d/syncable_schemas" (mt/id)))))
        (testing "Non-admins don't have permission to see syncable schemas"
          (is (= "You don't have permissions to do that."
                 (mt/user-http-request :rasta :get 403 (format "database/%d/syncable_schemas" (mt/id))))))))))

(deftest get-schemas-for-schemas-with-no-visible-tables
  (mt/with-temp* [Database [{db-id :id}]
                  Table    [_ {:db_id db-id, :schema "schema_1a", :name "table_1"}]
                  Table    [_ {:db_id db-id, :schema "schema_1c", :name "table_1"}] ;; out of order for sorting
                  Table    [_ {:db_id db-id, :schema "schema_1b", :name "table_1"}]
                  ;; table is not visible. Any non-nil value of `visibility_type` means Table shouldn't be visible
                  Table    [_ {:db_id db-id, :schema "schema_2", :name "table_2a", :visibility_type "hidden"}]
                  Table    [_ {:db_id db-id, :schema "schema_2", :name "table_2b", :visibility_type "cruft"}]
                    ;; table is not active
                  Table    [_ {:db_id db-id, :schema "schema_3", :name "table_3", :active false}]]
    (testing "GET /api/database/:id/schemas should not return schemas with no VISIBLE TABLES"
      (is (= ["schema_1a" "schema_1b" "schema_1c"]
             (mt/user-http-request :crowberto :get 200 (format "database/%d/schemas" db-id)))))
    (testing "GET /api/database/:id/schemas?include_hidden=true should return schemas with no VISIBLE TABLES"
      (is (= ["schema_1a" "schema_1b" "schema_1c" "schema_2"]
             (mt/user-http-request :crowberto :get 200 (format "database/%d/schemas?include_hidden=true" db-id)))))))

(deftest get-schemas-permissions-test
  (testing "GET /api/database/:id/schemas against permissions"
    (mt/with-temp* [Database [{db-id :id}]
                    Table    [t1 {:db_id db-id, :schema "schema1"}]
                    Table    [t2 {:db_id db-id, :schema "schema1"}]]
      (testing "should work if user has full DB perms..."
        (is (= ["schema1"]
               (mt/user-http-request :rasta :get 200 (format "database/%d/schemas" db-id)))))

      (testing "...or full schema perms..."
        (perms/revoke-data-perms! (perms-group/all-users) db-id)
        (perms/grant-permissions!  (perms-group/all-users) db-id "schema1")
        (is (= ["schema1"]
               (mt/user-http-request :rasta :get 200 (format "database/%d/schemas" db-id)))))

      (testing "...or just table read perms..."
        (perms/revoke-data-perms! (perms-group/all-users) db-id)
        (perms/revoke-data-perms! (perms-group/all-users) db-id "schema1")
        (perms/grant-permissions!  (perms-group/all-users) db-id "schema1" t1)
        (perms/grant-permissions!  (perms-group/all-users) db-id "schema1" t2)
        (is (= ["schema1"]
               (mt/user-http-request :rasta :get 200 (format "database/%d/schemas" db-id)))))

      (testing "should return a 403 for a user that doesn't have read permissions for the database"
        (perms/revoke-data-perms! (perms-group/all-users) db-id)
        (is (= "You don't have permissions to do that."
               (mt/user-http-request :rasta :get 403 (format "database/%s/schemas" db-id)))))

      (testing "should return a 403 if there are no perms for any schema"
        (perms/grant-full-data-permissions! (perms-group/all-users) db-id)
        (perms/revoke-data-perms! (perms-group/all-users) db-id "schema1")
        (is (= "You don't have permissions to do that."
               (mt/user-http-request :rasta :get 403 (format "database/%s/schemas" db-id))))))

    (testing "should exclude schemas for which the user has no perms"
      (mt/with-temp* [Database [{database-id :id}]
                      Table    [_ {:db_id database-id, :schema "schema-with-perms"}]
                      Table    [_ {:db_id database-id, :schema "schema-without-perms"}]]
        (perms/revoke-data-perms! (perms-group/all-users) database-id)
        (perms/grant-permissions! (perms-group/all-users) database-id "schema-with-perms")
        (is (= ["schema-with-perms"]
               (mt/user-http-request :rasta :get 200 (format "database/%s/schemas" database-id))))))))

(deftest get-schema-tables-test
  (testing "GET /api/database/:id/schema/:schema"
    (testing "Should return a 404 if the database isn't found"
      (is (= "Not found."
             (mt/user-http-request :crowberto :get 404 (format "database/%s/schema/%s" Integer/MAX_VALUE "schema1")))))
    (testing "Should return a 404 if the schema isn't found"
      (mt/with-temp* [Database [{db-id :id}]
                      Table    [_ {:db_id db-id, :schema "schema1"}]]
        (is (= "Not found."
               (mt/user-http-request :crowberto :get 404 (format "database/%d/schema/%s" db-id "not schema1"))))))

    (testing "should exclude Tables for which the user has no perms"
      (mt/with-temp* [Database [{database-id :id}]
                      Table    [table-with-perms {:db_id database-id, :schema "public", :name "table-with-perms"}]
                      Table    [_                {:db_id database-id, :schema "public", :name "table-without-perms"}]]
        (perms/revoke-data-perms! (perms-group/all-users) database-id)
        (perms/grant-permissions! (perms-group/all-users) database-id "public" table-with-perms)
        (is (= ["table-with-perms"]
               (map :name (mt/user-http-request :rasta :get 200 (format "database/%s/schema/%s" database-id "public")))))))

    (testing "should exclude inactive Tables"
      (mt/with-temp* [Database [{database-id :id}]
                      Table    [_ {:db_id database-id, :schema "public", :name "table"}]
                      Table    [_ {:db_id database-id, :schema "public", :name "inactive-table", :active false}]]
        (is (= ["table"]
               (map :name (mt/user-http-request :rasta :get 200 (format "database/%s/schema/%s" database-id "public")))))))

    (testing "should exclude hidden Tables"
      (mt/with-temp* [Database [{database-id :id}]
                      Table    [_ {:db_id database-id, :schema "public", :name "table"}]
                      Table    [_ {:db_id database-id, :schema "public", :name "hidden-table", :visibility_type "hidden"}]]
        (is (= ["table"]
               (map :name (mt/user-http-request :rasta :get 200 (format "database/%s/schema/%s" database-id "public")))))))

    (testing "should show hidden Tables when explicitly asked for"
      (mt/with-temp* [Database [{database-id :id}]
                      Table    [_ {:db_id database-id, :schema "public", :name "table"}]
                      Table    [_ {:db_id database-id, :schema "public", :name "hidden-table", :visibility_type "hidden"}]]
        (is (= #{"table" "hidden-table"}
               (set (map :name (mt/user-http-request :rasta :get 200 (format "database/%s/schema/%s" database-id "public")
                                                     :include_hidden true)))))))

    (testing "should work for the saved questions 'virtual' database"
      (mt/with-temp* [Collection [coll   {:name "My Collection"}]
                      Card       [card-1 (assoc (card-with-native-query "Card 1") :collection_id (:id coll))]
                      Card       [card-2 (card-with-native-query "Card 2")]]
        ;; run the cards to populate their result_metadata columns
        (doseq [card [card-1 card-2]]
          (mt/user-http-request :crowberto :post 202 (format "card/%d/query" (u/the-id card))))
        (testing "Should be able to get saved questions in a specific collection"
          (is (= [{:id               (format "card__%d" (:id card-1))
                   :db_id            (mt/id)
                   :moderated_status nil
                   :display_name     "Card 1"
                   :schema           "My Collection"
                   :description      nil}]
                 (mt/user-http-request :lucky :get 200
                                       (format "database/%d/schema/My Collection" mbql.s/saved-questions-virtual-database-id)))))

        (testing "Should be able to get saved questions in the root collection"
          (let [response (mt/user-http-request :lucky :get 200
                                               (format "database/%d/schema/%s" mbql.s/saved-questions-virtual-database-id (api.table/root-collection-schema-name)))]
            (is (schema= [{:id               #"^card__\d+$"
                           :db_id            s/Int
                           :display_name     s/Str
                           :moderated_status (s/enum nil "verified")
                           :schema           (s/eq (api.table/root-collection-schema-name))
                           :description      (s/maybe s/Str)}]
                         response))
            (is (not (contains? (set (map :display_name response)) "Card 3")))
            (is (contains? (set response)
                           {:id               (format "card__%d" (:id card-2))
                            :db_id            (mt/id)
                            :display_name     "Card 2"
                            :moderated_status nil
                            :schema           (api.table/root-collection-schema-name)
                            :description      nil}))))

        (testing "Should throw 404 if the schema/Collection doesn't exist"
          (is (= "Not found."
                 (mt/user-http-request :lucky :get 404
                                       (format "database/%d/schema/Coin Collection" mbql.s/saved-questions-virtual-database-id)))))))
    (testing "should work for the datasets in the 'virtual' database"
      (mt/with-temp* [Collection [coll   {:name "My Collection"}]
                      Card       [card-1 (assoc (card-with-native-query "Card 1")
                                                :collection_id (:id coll)
                                                :dataset true)]
                      Card       [card-2 (assoc (card-with-native-query "Card 2")
                                                :dataset true)]
                      Card       [_card-3 (assoc (card-with-native-query "error")
                                                 ;; regular saved question should not be in the results
                                                 :dataset false)]]
        ;; run the cards to populate their result_metadata columns
        (doseq [card [card-1 card-2]]
          (is (=? {:status "completed"}
                  (mt/user-http-request :crowberto :post 202 (format "card/%d/query" (u/the-id card))))))
        (testing "Should be able to get datasets in a specific collection"
          (is (= [{:id               (format "card__%d" (:id card-1))
                   :db_id            (mt/id)
                   :moderated_status nil
                   :display_name     "Card 1"
                   :schema           "My Collection"
                   :description      nil}]
                 (mt/user-http-request :lucky :get 200
                                       (format "database/%d/datasets/My Collection" mbql.s/saved-questions-virtual-database-id)))))

        (testing "Should be able to get datasets in the root collection"
          (let [response (mt/user-http-request :lucky :get 200
                                               (format "database/%d/datasets/%s" mbql.s/saved-questions-virtual-database-id (api.table/root-collection-schema-name)))]
            (is (schema= [{:id               #"^card__\d+$"
                           :db_id            s/Int
                           :display_name     s/Str
                           :moderated_status (s/enum nil "verified")
                           :schema           (s/eq (api.table/root-collection-schema-name))
                           :description      (s/maybe s/Str)}]
                         response))
            (is (contains? (set response)
                           {:id               (format "card__%d" (:id card-2))
                            :db_id            (mt/id)
                            :display_name     "Card 2"
                            :moderated_status nil
                            :schema           (api.table/root-collection-schema-name)
                            :description      nil}))))

        (testing "Should throw 404 if the schema/Collection doesn't exist"
          (is (= "Not found."
                 (mt/user-http-request :lucky :get 404
                                       (format "database/%d/schema/Coin Collection" mbql.s/saved-questions-virtual-database-id)))))))

    (mt/with-temp* [Database [{db-id :id}]
                    Table    [_ {:db_id db-id, :schema nil, :name "t1"}]
                    Table    [_ {:db_id db-id, :schema "", :name "t2"}]]
      (testing "to fetch Tables with `nil` or empty schemas, use the blank string"
        (is (= ["t1" "t2"]
               (map :name (mt/user-http-request :lucky :get 200 (format "database/%d/schema/" db-id)))))))))

(deftest get-schema-tables-permissions-test
  (testing "GET /api/database/:id/schema/:schema against permissions"
    (mt/with-temp* [Database [{db-id :id}]
                    Table    [t1  {:db_id db-id, :schema "schema1", :name "t1"}]
                    Table    [_t2 {:db_id db-id, :schema "schema2"}]
                    Table    [t3  {:db_id db-id, :schema "schema1", :name "t3"}]]
      (testing "if we have full DB perms"
        (is (= ["t1" "t3"]
               (map :name (mt/user-http-request :rasta :get 200 (format "database/%d/schema/%s" db-id "schema1"))))))

      (testing "if we have full schema perms"
        (perms/revoke-data-perms! (perms-group/all-users) db-id)
        (perms/grant-permissions! (perms-group/all-users) db-id "schema1")
        (is (= ["t1" "t3"]
               (map :name (mt/user-http-request :rasta :get 200 (format "database/%d/schema/%s" db-id "schema1"))))))

      (testing "if we have full Table perms"
        (perms/revoke-data-perms! (perms-group/all-users) db-id)
        (perms/revoke-data-perms! (perms-group/all-users) db-id "schema1")
        (perms/grant-permissions! (perms-group/all-users) db-id "schema1" t1)
        (perms/grant-permissions! (perms-group/all-users) db-id "schema1" t3)
        (is (= ["t1" "t3"]
               (map :name (mt/user-http-request :rasta :get 200 (format "database/%d/schema/%s" db-id "schema1")))))))

    (testing "should return a 403 for a user that doesn't have read permissions"
      (testing "for the DB"
        (mt/with-temp* [Database [{database-id :id}]
                        Table    [_ {:db_id database-id, :schema "test"}]]
          (perms/revoke-data-perms! (perms-group/all-users) database-id)
          (is (= "You don't have permissions to do that."
                 (mt/user-http-request :rasta :get 403 (format "database/%s/schema/%s" database-id "test"))))))

      (testing "for the schema"
        (mt/with-temp* [Database [{database-id :id}]
                        Table    [_ {:db_id database-id, :schema "schema-with-perms"}]
                        Table    [_ {:db_id database-id, :schema "schema-without-perms"}]]
          (perms/revoke-data-perms! (perms-group/all-users) database-id)
          (perms/grant-permissions! (perms-group/all-users) database-id "schema-with-perms")
          (is (= "You don't have permissions to do that."
                 (mt/user-http-request :rasta :get 403 (format "database/%s/schema/%s" database-id "schema-without-perms")))))))))

(deftest slashes-in-identifiers-test
  (testing "We should handle Databases with slashes in identifiers correctly (#12450)"
    (t2.with-temp/with-temp [Database {db-id :id} {:name "my/database"}]
      (doseq [schema-name ["my/schema"
                           "my//schema"
                           "my\\schema"
                           "my\\\\schema"
                           "my\\//schema"
                           "my_schema/"
                           "my_schema\\"]]
        (testing (format "\nschema name = %s" (pr-str schema-name))
          (t2.with-temp/with-temp [Table _ {:db_id db-id, :schema schema-name, :name "my/table"}]
            (testing "\nFetch schemas"
              (testing "\nGET /api/database/:id/schemas/"
                (is (= [schema-name]
                       (mt/user-http-request :rasta :get 200 (format "database/%d/schemas" db-id))))))
            (testing (str "\nFetch schema tables -- should work if you URL escape the schema name"
                          "\nGET /api/database/:id/schema/:schema")
              (let [url (format "database/%d/schema/%s" db-id (codec/url-encode schema-name))]
                (testing (str "\nGET /api/" url)
                  (is (schema= [{:schema (s/eq schema-name)
                                 s/Keyword s/Any}]
                               (mt/user-http-request :rasta :get 200 url))))))))))))

(deftest upsert-sensitive-values-test
  (testing "empty maps are okay"
    (is (= {}
           (api.database/upsert-sensitive-fields {} {}))))
  (testing "no details updates are okay"
    (is (= nil
           (api.database/upsert-sensitive-fields nil nil))))
  (testing "fields are replaced"
    (is (= {:use-service-account           nil
            :dataset-id                    "dacort"
            :use-jvm-timezone              false
            :service-account-json          "{\"foo\": \"bar\"}"
            :password                      "foo"
            :pass                          "bar"
            :tunnel-pass                   "quux"
            :tunnel-private-key            "foobar"
            :tunnel-private-key-passphrase "fooquux"
            :access-token                  "foobarfoo"
            :refresh-token                 "foobarquux"}
           (api.database/upsert-sensitive-fields {:description nil
                                                  :name        "customer success BQ"
                                                  :details     {:use-service-account           nil
                                                                :dataset-id                    "dacort"
                                                                :service-account-json          "{}"
                                                                :use-jvm-timezone              false
                                                                :password                      "password"
                                                                :pass                          "pass"
                                                                :tunnel-pass                   "tunnel-pass"
                                                                :tunnel-private-key            "tunnel-private-key"
                                                                :tunnel-private-key-passphrase "tunnel-private-key-passphrase"
                                                                :access-token                  "access-token"
                                                                :refresh-token                 "refresh-token"}
                                                  :id          2}
                                                 {:service-account-json          "{\"foo\": \"bar\"}"
                                                  :password                      "foo"
                                                  :pass                          "bar"
                                                  :tunnel-pass                   "quux"
                                                  :tunnel-private-key            "foobar"
                                                  :tunnel-private-key-passphrase "fooquux"
                                                  :access-token                  "foobarfoo"
                                                  :refresh-token                 "foobarquux"}))))
  (testing "no fields are replaced"
    (is (= {:use-service-account           nil
            :dataset-id                    "dacort"
            :use-jvm-timezone              false
            :service-account-json          "{}"
            :password                      "password"
            :pass                          "pass"
            :tunnel-pass                   "tunnel-pass"
            :tunnel-private-key            "tunnel-private-key"
            :tunnel-private-key-passphrase "tunnel-private-key-passphrase"
            :access-token                  "access-token"
            :refresh-token                 "refresh-token"}
           (api.database/upsert-sensitive-fields {:description nil
                                                  :name        "customer success BQ"
                                                  :details     {:use-service-account           nil
                                                                :dataset-id                    "dacort"
                                                                :use-jvm-timezone              false
                                                                :service-account-json          "{}"
                                                                :password                      "password"
                                                                :pass                          "pass"
                                                                :tunnel-pass                   "tunnel-pass"
                                                                :tunnel-private-key            "tunnel-private-key"
                                                                :tunnel-private-key-passphrase "tunnel-private-key-passphrase"
                                                                :access-token                  "access-token"
                                                                :refresh-token                 "refresh-token"}
                                                  :id          2}
                                                 {:service-account-json          protected-password
                                                  :password                      protected-password
                                                  :pass                          protected-password
                                                  :tunnel-pass                   protected-password
                                                  :tunnel-private-key            protected-password
                                                  :tunnel-private-key-passphrase protected-password
                                                  :access-token                  protected-password
                                                  :refresh-token                 protected-password}))))

  (testing "only one field is replaced"
    (is (= {:use-service-account           nil
            :dataset-id                    "dacort"
            :use-jvm-timezone              false
            :service-account-json          "{}"
            :password                      "new-password"
            :pass                          "pass"
            :tunnel-pass                   "tunnel-pass"
            :tunnel-private-key            "tunnel-private-key"
            :tunnel-private-key-passphrase "tunnel-private-key-passphrase"
            :access-token                  "access-token"
            :refresh-token                 "refresh-token"}
           (api.database/upsert-sensitive-fields {:description nil
                                                  :name        "customer success BQ"
                                                  :details     {:use-service-account           nil
                                                                :dataset-id                    "dacort"
                                                                :use-jvm-timezone              false
                                                                :service-account-json          "{}"
                                                                :password                      "password"
                                                                :pass                          "pass"
                                                                :tunnel-pass                   "tunnel-pass"
                                                                :tunnel-private-key            "tunnel-private-key"
                                                                :tunnel-private-key-passphrase "tunnel-private-key-passphrase"
                                                                :access-token                  "access-token"
                                                                :refresh-token                 "refresh-token"}
                                                  :id          2}
                                                 {:service-account-json          protected-password
                                                  :password                      "new-password"
                                                  :pass                          protected-password
                                                  :tunnel-pass                   protected-password
                                                  :tunnel-private-key            protected-password
                                                  :tunnel-private-key-passphrase protected-password
                                                  :access-token                  protected-password
                                                  :refresh-token                 protected-password})))))


(deftest secret-file-paths-returned-by-api-test
  (mt/with-driver :secret-test-driver
    (testing "File path values for secrets are returned as plaintext in the API (#20030)"
      (t2.with-temp/with-temp [Database database {:engine  :secret-test-driver
                                                  :name    "Test secret DB with password path"
                                                  :details {:host           "localhost"
                                                            :password-path "/path/to/password.txt"}}]
        (is (= {:password-source "file-path"
                :password-value  "/path/to/password.txt"}
               (as-> (u/the-id database) d
                 (format "database/%d" d)
                 (mt/user-http-request :crowberto :get 200 d)
                 (:details d)
                 (select-keys d [:password-source :password-value]))))))))

;; these descriptions use deferred-tru because the `defsetting` macro complains if they're not, but since these are in
;; tests they won't get scraped for i18n purposes so it's ok.
(defsetting test-db-local-setting-public
  (deferred-tru "Test Database-local Setting with internal visibility.")
  :database-local :only
  :visibility :public
  :type :integer)

(defsetting test-db-local-setting-authenticated
  (deferred-tru "Test Database-local Setting with internal visibility.")
  :database-local :only
  :visibility :authenticated
  :type :integer)

(defsetting test-db-local-setting-admin
  (deferred-tru "Test Database-local Setting with internal visibility.")
  :database-local :only
  :visibility :admin
  :type :integer)

(defsetting test-db-local-setting-internal
  "Test Database-local Setting with internal visibility."
  :database-local :only
  :visibility :internal
  :type :integer)

(deftest database-local-settings-come-back-with-database-test
  (testing "Database-local Settings should come back with"
    (mt/with-temp-vals-in-db Database (mt/id) {:settings {:test-db-local-setting-public        1
                                                          :test-db-local-setting-authenticated 1
                                                          :test-db-local-setting-admin         1
                                                          :test-db-local-setting-internal      1}}
      (doseq [[user user-type] {:crowberto :admin, :rasta :non-admin}]
        (doseq [{:keys [endpoint response]} [{:endpoint "GET /api/database/:id"
                                              :response (fn []
                                                          (mt/user-http-request user :get 200 (format "database/%d" (mt/id))))}
                                             {:endpoint "GET /api/database"
                                              :response (fn []
                                                          (some
                                                           (fn [database]
                                                             (when (= (:id database) (mt/id))
                                                               database))
                                                           (:data (mt/user-http-request user :get 200 "database"))))}]]
          (testing endpoint
            (let [{:keys [settings], :as response} (response)]
              (is (map? response))
              (is (map? settings))
              (doseq [{:keys [setting visible?]} [{:setting  :test-db-local-setting-public
                                                   :visible? (if (= user-type :non-admin) true true)}
                                                  {:setting  :test-db-local-setting-authenticated
                                                   :visible? (if (= user-type :non-admin) true true)}
                                                  {:setting  :test-db-local-setting-admin
                                                   :visible? (if (= user-type :non-admin) false true)}
                                                  {:setting  :test-db-local-setting-internal
                                                   :visible? (if (= user-type :non-admin) false false)}]
                      :let                       [{:keys [visibility]} (setting/resolve-setting setting)]]
                (testing (format "\nIf Setting visibility is %s, %s user should %s be able to see its value"
                                 visibility user-type (if visible? "SHOULD" "SHOULD NOT"))
                  (testing (format "\nresponse = %s" (u/pprint-to-str response))
                    (if visible?
                      (is (partial= {setting 1}
                                    settings))
                      (is (not (contains? settings setting))))))))))))))

(deftest admins-set-database-local-settings-test
  (testing "Admins should be allowed to update Database-local Settings (#19409)"
    (mt/with-temp-vals-in-db Database (mt/id) {:settings nil}
      (letfn [(settings []
                (t2/select-one-fn :settings Database :id (mt/id)))
              (set-settings! [m]
                (with-redefs [h2/*allow-testing-h2-connections* true]
                  (u/prog1 (mt/user-http-request :crowberto :put 200 (format "database/%d" (mt/id))
                                                 {:settings m})
                    (is (=? {:id (mt/id)}
                            <>)))))]
        (testing "Should initially be nil"
          (is (nil? (settings))))
        (testing "Set initial value"
          (testing "response"
            (is (partial= {:settings {:max-results-bare-rows 1337}}
                          (set-settings! {:max-results-bare-rows 1337}))))
          (testing "App DB"
            (is (= {:max-results-bare-rows 1337}
                   (settings)))))
        (testing "Setting a different value should not affect anything not specified (PATCH-style update)"
          (testing "response"
            (is (partial= {:settings {:max-results-bare-rows   1337
                                      :database-enable-actions true}}
                          (set-settings! {:database-enable-actions true}))))
          (testing "App DB"
            (is (= {:max-results-bare-rows   1337
                    :database-enable-actions true}
                   (settings)))))
        (testing "Update existing value"
          (testing "response"
            (is (partial= {:settings {:max-results-bare-rows   1337
                                      :database-enable-actions false}}
                          (set-settings! {:database-enable-actions false}))))
          (testing "App DB"
            (is (= {:max-results-bare-rows   1337
                    :database-enable-actions false}
                   (settings)))))
        (testing "Unset a value"
          (testing "response"
            (is (partial= {:settings {:database-enable-actions false}}
                          (set-settings! {:max-results-bare-rows nil}))))
          (testing "App DB"
            (is (= {:database-enable-actions false}
                   (settings)))))))))

(deftest persist-database-test-2
  (mt/test-drivers (mt/normal-drivers-with-feature :persist-models)
    (mt/dataset test-data
      (let [db-id (:id (mt/db))]
        (t2.with-temp/with-temp
          [Card card {:database_id db-id
                      :dataset     true}]
          (mt/with-temporary-setting-values [persisted-models-enabled false]
            (testing "requires persist setting to be enabled"
              (is (= "Persisting models is not enabled."
                     (mt/user-http-request :crowberto :post 400 (str "database/" db-id "/persist"))))))

          (mt/with-temporary-setting-values [persisted-models-enabled true]
            (testing "only users with permissions can persist a database"
              (is (= "You don't have permissions to do that."
                     (mt/user-http-request :rasta :post 403 (str "database/" db-id "/persist")))))

            (testing "should be able to persit an database"
              (mt/user-http-request :crowberto :post 204 (str "database/" db-id "/persist"))
              (is (= "creating" (t2/select-one-fn :state 'PersistedInfo
                                                  :database_id db-id
                                                  :card_id     (:id card))))
              (is (true? (t2/select-one-fn (comp :persist-models-enabled :settings)
                                           Database
                                           :id db-id))))
            (testing "it's okay to trigger persist even though the database is already persisted"
              (mt/user-http-request :crowberto :post 204 (str "database/" db-id "/persist")))))))))

(deftest unpersist-database-test
  (mt/test-drivers (mt/normal-drivers-with-feature :persist-models)
    (mt/dataset test-data
      (let [db-id (:id (mt/db))]
        (t2.with-temp/with-temp
          [Card     _ {:database_id db-id
                       :dataset     true}]
          (testing "only users with permissions can persist a database"
            (is (= "You don't have permissions to do that."
                   (mt/user-http-request :rasta :post 403 (str "database/" db-id "/unpersist")))))

          (mt/with-temporary-setting-values [persisted-models-enabled true]
            (testing "should be able to persit an database"
              ;; trigger persist first
              (mt/user-http-request :crowberto :post 204 (str "database/" db-id "/unpersist"))
              (is (nil? (t2/select-one-fn (comp :persist-models-enabled :settings)
                                          Database
                                          :id db-id))))
            (testing "it's okay to unpersist even though the database is not persisted"
              (mt/user-http-request :crowberto :post 204 (str "database/" db-id "/unpersist")))))))))
