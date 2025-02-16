(ns metabase.models.data-permissions
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [malli.core :as mc]
   [medley.core :as m]
   [metabase.api.common :as api]
   [metabase.config :as config]
   [metabase.models.interface :as mi]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.malli :as mu]
   [methodical.core :as methodical]
   [toucan2.core :as t2])
  (:import
   (clojure.lang PersistentVector)))

(set! *warn-on-reflection* true)

(doto :model/DataPermissions
  (derive :metabase/model))

(methodical/defmethod t2/table-name :model/DataPermissions [_model] :data_permissions)

(t2/deftransforms :model/DataPermissions
  {:perm_type  mi/transform-keyword
   :perm-type  mi/transform-keyword
   :perm_value mi/transform-keyword
   ;; define keyword transformation for :type and :value as well so that we can use them as aliases
   :type       mi/transform-keyword
   :value      mi/transform-keyword})


;;; ---------------------------------------- Permission definitions ---------------------------------------------------

;; IMPORTANT: If you add a new permission type, `:values` must be ordered from *most* permissive to *least* permissive.
;;
;;  - When fetching a user's permissions, the default behavior is to return the *most* permissive value from any group the
;;    user is in. This can be overridden by definding a custom implementation of `coalesce`.
;;
;;  - If a user does not have any value for the permission when it is fetched, the *least* permissive value is used as a
;;    fallback.


(def Permissions
  "Permissions which apply to individual databases or tables"
  {:perms/data-access           {:model :model/Table :values [:unrestricted :no-self-service :block]}
   :perms/download-results      {:model :model/Table :values [:one-million-rows :ten-thousand-rows :no]}
   :perms/manage-table-metadata {:model :model/Table :values [:yes :no]}

   :perms/native-query-editing  {:model :model/Database :values [:yes :no]}
   :perms/manage-database       {:model :model/Database :values [:yes :no]}})

(def PermissionType
  "Malli spec for valid permission types."
  (into [:enum {:error/message "Invalid permission type"}]
        (keys Permissions)))

(def PermissionValue
  "Malli spec for a keyword that matches any value in [[Permissions]]."
  (into [:enum {:error/message "Invalid permission value"}]
        (distinct (mapcat :values (vals Permissions)))))


;;; ------------------------------------------- Misc Utils ------------------------------------------------------------

(defn least-permissive-value
  "The *least* permissive value for a given perm type. This value is used as a fallback when a user does not have a
  value for the permission in the database."
  [perm-type]
  (-> Permissions perm-type :values last))

(defn most-permissive-value
  "The *most* permissive value for a given perm type. This is the default value for superusers."
  [perm-type]
  (-> Permissions perm-type :values first))

(mu/defn at-least-as-permissive?
  "Returns true if value1 is at least as permissive as value2 for the given permission type."
  [perm-type :- PermissionType
   value1    :- PermissionValue
   value2    :- PermissionValue]
  (let [^PersistentVector values (-> Permissions perm-type :values)]
    (<= (.indexOf values value1)
        (.indexOf values value2))))

(def ^:private model-by-perm-type
  "A map from permission types directly to model identifiers (or `nil`)."
  (update-vals Permissions :model))

(defn- assert-value-matches-perm-type
  [perm-type perm-value]
  (when-not (contains? (set (get-in Permissions [perm-type :values])) perm-value)
    (throw (ex-info (tru "Permission type {0} cannot be set to {1}" perm-type perm-value)
                    {perm-type (Permissions perm-type)}))))

;;; ---------------------------------------- Caching ------------------------------------------------------------------

(defn relevant-permissions-for-user
  "Returns all relevant rows for permissions for the user"
  [user-id]
  (->> (t2/select :model/DataPermissions
                  {:select [:p.* [:pgm.user_id :user_id]]
                   :from [[:permissions_group_membership :pgm]]
                   :join [[:permissions_group :pg] [:= :pg.id :pgm.group_id]
                          [:data_permissions :p] [:= :p.group_id :pg.id]]
                   :where [:= :pgm.user_id user-id]})
       (reduce (fn [m {:keys [user_id perm_type db_id] :as row}]
                 (update-in m [user_id perm_type db_id] (fnil conj []) row))
               {})))

(defn- relevant-permissions-for-user-perm-and-db
  "Returns all relevant rows for a given user, permission type, and db_id"
  [user-id perm-type db-id]
  (t2/select :model/DataPermissions
             {:select [:p.* [:pgm.user_id :user_id]]
              :from [[:permissions_group_membership :pgm]]
              :join [[:permissions_group :pg] [:= :pg.id :pgm.group_id]
                     [:data_permissions :p] [:= :p.group_id :pg.id]]
              :where [:and
                      [:= :pgm.user_id user-id]
                      [:= :p.perm_type (u/qualified-name perm-type)]
                      [:= :p.db_id db-id]]}))

(def ^:dynamic *permissions-for-user*
  "Filled by `with-relevant-permissions-for-user` with the output of `(relevant-permissions-for-user [user-id])`. A map
  with keys like `[user_id perm_type db_id]`, the latter two because nearly always we want to get permissions for a
  particular permission type and database id, `user_id` because we want to be VERY sure that we never accidentally use
  the cache for the wrong user (e.g. if we were checking whether *another* user could perform some action for some
  reason). Of course, we also won't use the cache if we're not checking for the current user - but better safe than
  sorry. The values are collections of rows of DataPermissions."
  (delay nil))

(defmacro with-relevant-permissions-for-user
  "Populates the `*permissions-for-user*` dynamic var for use by the cache-aware functions in this namespace."
  [user-id & body]
  `(binding [*permissions-for-user* (delay (relevant-permissions-for-user ~user-id))]
     ~@body))

(defn- get-permissions [user-id perm-type db-id]
  (if (and (= user-id api/*current-user-id*)
           (get @*permissions-for-user* user-id))
    (get-in @*permissions-for-user* [user-id perm-type db-id])
    (relevant-permissions-for-user-perm-and-db user-id perm-type db-id)))

;;; ---------------------------------------- Fetching a user's permissions --------------------------------------------

(defmulti coalesce
  "Coalesce a set of permission values into a single value. This is used to determine the permission to enforce for a
  user in multiple groups with conflicting permissions. By default, this returns the *most* permissive value that the
  user has in any group.

  For instance,
  - Given an empty set, we return the most permissive.
    (coalesce :settings-access #{}) => :yes
  - Given a set with values, we select the most permissive option in the set.
    (coalesce :settings-access #{:view :no-access}) => :view"
  {:arglists '([perm-type perm-values])}
  (fn [perm-type _perm-values] perm-type))

(defmethod coalesce :default
  [perm-type perm-values]
  (let [ordered-values (-> Permissions perm-type :values)]
    (first (filter (set perm-values) ordered-values))))

(defmethod coalesce :perms/data-access
  [perm-type perm-values]
  (let [perm-values    (set perm-values)
        ordered-values (-> Permissions perm-type :values)]
    (if (and (perm-values :block)
             (not (perm-values :unrestricted)))
      ;; Block in one group overrides no-self-service in another, but not unrestricted
      :block
      (first (filter perm-values ordered-values)))))

(defn coalesce-most-restrictive
  "In some cases (fetching schema permissions) we need to coalesce permissions using the most restrictive option."
  [perm-type perm-values]
  (let [ordered-values (-> Permissions perm-type :values reverse)]
    (first (filter (set perm-values) ordered-values))))

(defn- is-superuser?
  [user-id]
  (if (= user-id api/*current-user-id*)
    api/*is-superuser?*
    (t2/select-one-fn :is_superuser :model/User :id user-id)))

(mu/defn database-permission-for-user :- PermissionValue
  "Returns the effective permission value for a given user, permission type, and database ID. If the user has
  multiple permissions for the given type in different groups, they are coalesced into a single value.

  For permissions which can be set at the table-level or the database-level, this function will return the database-level
  permission if the user has it."
  [user-id perm-type database-id]
  (when (not= :model/Database (model-by-perm-type perm-type))
    (throw (ex-info (tru "Permission type {0} is a table-level permission." perm-type)
                    {perm-type (Permissions perm-type)})))
  (if (is-superuser? user-id)
    (most-permissive-value perm-type)
    (let [perm-values (->> (get-permissions user-id perm-type database-id)
                           (map :perm_value)
                           (into #{}))]
      (or (coalesce perm-type perm-values)
          (least-permissive-value perm-type)))))

(mu/defn user-has-permission-for-database? :- :boolean
  "Returns a Boolean indicating whether the user has the specified permission value for the given database ID and table ID,
   or a more permissive value."
  [user-id perm-type perm-value database-id]
  (at-least-as-permissive? perm-type
                           (database-permission-for-user user-id perm-type database-id)
                           perm-value))

(def ^:dynamic *additional-table-permissions*
  "See the `with-additional-table-permission` macro below."
  {})

(defmacro with-additional-table-permission
  "Sometimes, for sandboxing, we need to run something in a context with additional permissions - for example, so that a
  user can read a table to which they have only sandboxed access.

  I intentionally did *not* build this as a general-purpose 'add an additional context' macro, because supporting it
  for every function in the DataPermission API will be challenging, and the API is still in flux. Instead, for now,
  this is a very tightly constrained macro that only adds an additional *table* level permission, and only affects the
  output of `table-permission-for-user`."
  [perm-type database-id table-id perm-value & form]
  `(binding [*additional-table-permissions* (assoc-in *additional-table-permissions*
                                                      [~database-id ~table-id ~perm-type]
                                                      ~perm-value)]
     ~@form))

(defn- get-additional-table-permission! [{:keys [db-id table-id]} perm-type]
  (get-in *additional-table-permissions* [db-id table-id perm-type]))

(mu/defn table-permission-for-group :- PermissionValue
  "Returns the effective permission value for a given *group*, permission type, and database ID, and table ID."
  [group-id perm-type database-id table-id]
  (when (not= :model/Table (model-by-perm-type perm-type))
    (throw (ex-info (tru "Permission type {0} is a table-level permission." perm-type)
                    {perm-type (Permissions perm-type)})))
  (let [perm-values (t2/select-fn-set :value
                                      :model/DataPermissions
                                      {:select [[:p.perm_value :value]]
                                       :from [[:data_permissions :p]]
                                       :where [:and
                                               [:= :p.group_id group-id]
                                               [:= :p.perm_type (u/qualified-name perm-type)]
                                               [:= :p.db_id database-id]
                                               [:or
                                                [:= :table_id table-id]
                                                [:= :table_id nil]]]})]
    (or (coalesce perm-type (conj perm-values (get-additional-table-permission! {:db-id database-id :table-id table-id}
                                                                                perm-type)))
        (least-permissive-value perm-type))))

(mu/defn table-permission-for-user :- PermissionValue
  "Returns the effective permission value for a given user, permission type, and database ID, and table ID. If the user
  has multiple permissions for the given type in different groups, they are coalesced into a single value."
  [user-id perm-type database-id table-id]
  (when (not= :model/Table (model-by-perm-type perm-type))
    (throw (ex-info (tru "Permission type {0} is a database-level permission." perm-type)
                    {perm-type (Permissions perm-type)})))
  (if (is-superuser? user-id)
    (most-permissive-value perm-type)
    (let [perm-values (->> (get-permissions user-id perm-type database-id)
                           (filter #(or (= (:table_id %) table-id)
                                        (nil? (:table_id %))))
                           (map :perm_value)
                           (into #{}))]
      (or (coalesce perm-type (conj perm-values (get-additional-table-permission! {:db-id database-id :table-id table-id}
                                                                            perm-type)))
          (least-permissive-value perm-type)))))

(mu/defn user-has-permission-for-table? :- :boolean
  "Returns a Boolean indicating whether the user has the specified permission value for the given database ID and table ID,
   or a more permissive value."
  [user-id perm-type perm-value database-id table-id]
  (at-least-as-permissive? perm-type
                           (table-permission-for-user user-id perm-type database-id table-id)
                           perm-value))

(defn- most-restrictive-per-group
  "Given a perm-type and a collection of maps that look like `{:group-id 1 :value :permission-value}`, returns a set
  containing the most restrictive permission value in each group."
  [perm-type perms]
  (->> perms
       (group-by :group-id)
       (m/map-vals (fn [ps]
                     (->> ps (map :value) set (coalesce-most-restrictive perm-type))))
       vals
       set))

(mu/defn full-schema-permission-for-user :- PermissionValue
  "Returns the effective *schema-level* permission value for a given user, permission type, and database ID, and
  schema name. If the user has multiple permissions for the given type in different groups, they are coalesced into a
  single value. The schema-level permission is the *most* restrictive table-level permission within that schema."
  [user-id perm-type database-id schema-name]
  (when (not= :model/Table (model-by-perm-type perm-type))
    (throw (ex-info (tru "Permission type {0} is not a table-level permission." perm-type)
                    {perm-type (Permissions perm-type)})))
  (if (is-superuser? user-id)
    (most-permissive-value perm-type)
    ;; The schema-level permission is the most-restrictive table-level permission within a schema. So for each group,
    ;; select the most-restrictive table-level permission. Then use normal coalesce logic to select the *least*
    ;; restrictive group permission.
    (let [perm-values (most-restrictive-per-group
                       perm-type
                       (->> (get-permissions user-id perm-type database-id)
                            (filter #(or (= (:schema_name %) schema-name)
                                         (nil? (:table_id %))))
                            (map #(set/rename-keys % {:group_id :group-id
                                                      :perm_value :value}))
                            (map #(select-keys % [:group-id :value]))))]
      (or (coalesce perm-type perm-values)
          (least-permissive-value perm-type)))))

(mu/defn full-db-permission-for-user :- PermissionValue
  "Returns the effective *db-level* permission value for a given user, permission type, and database ID. If the user
  has multiple permissions for the given type in different groups, they are coalesced into a single value. The
  db-level permission is the *most* restrictive table-level permission within that schema."
  [user-id perm-type database-id]
  (when (not= :model/Table (model-by-perm-type perm-type))
    (throw (ex-info (tru "Permission type {0} is not a table-level permission." perm-type)
                    {perm-type (Permissions perm-type)})))
  (if (is-superuser? user-id)
    (most-permissive-value perm-type)
    ;; The schema-level permission is the most-restrictive table-level permission within a schema. So for each group,
    ;; select the most-restrictive table-level permission. Then use normal coalesce logic to select the *least*
    ;; restrictive group permission.
    (let [perm-values (most-restrictive-per-group
                       perm-type
                       (->> (get-permissions user-id perm-type database-id)
                            (map #(set/rename-keys % {:group_id :group-id
                                                      :perm_value :value}))
                            (map #(select-keys % [:group-id :value]))))]
      (or (coalesce perm-type perm-values)
          (least-permissive-value perm-type)))))

(mu/defn schema-permission-for-user :- PermissionValue
  "Returns the effective *schema-level* permission value for a given user, permission type, and database ID, and
  schema name. If the user has multiple permissions for the given type in different groups, they are coalesced into a
  single value. The schema-level permission is the *least* restrictive table-level permission within that schema."
  [user-id perm-type database-id schema-name]
  (when (not= :model/Table (model-by-perm-type perm-type))
    (throw (ex-info (tru "Permission type {0} is not a table-level permission." perm-type)
                    {perm-type (Permissions perm-type)})))
  (if (is-superuser? user-id)
    (most-permissive-value perm-type)
    ;; The schema-level permission is the most-restrictive table-level permission within a schema. So for each group,
    ;; select the most-restrictive table-level permission. Then use normal coalesce logic to select the *least*
    ;; restrictive group permission.
    (let [perm-values (->> (get-permissions user-id perm-type database-id)
                           (filter #(or (= (:schema_name %) schema-name)
                                        (nil? (:table_id %))))
                           (map :perm_value)
                           (into #{}))]
      (or (coalesce perm-type perm-values)
          (least-permissive-value perm-type)))))

(mu/defn user-has-permission-for-schema? :- :boolean
  "Returns a Boolean indicating whether the user has the specified permission value for the given database ID and schema,
   or a more permissive value."
  [user-id perm-type perm-value database-id schema]
  (at-least-as-permissive? perm-type
                           (schema-permission-for-user user-id perm-type database-id schema)
                           perm-value))

(mu/defn most-permissive-database-permission-for-user :- PermissionValue
  "Similar to checking _partial_ permissions with permissions paths - what is the *most permissive* permission the
  user has on any of the tables within this database?"
  [user-id perm-type database-id]
  (when (not= :model/Table (model-by-perm-type perm-type))
    (throw (ex-info (tru "Permission type {0} is not a table-level permission." perm-type)
                    {perm-type (Permissions perm-type)})))
  (if (is-superuser? user-id)
    (most-permissive-value perm-type)
    (let [perm-values (->> (get-permissions user-id perm-type database-id)
                           (map :perm_value)
                           (into #{}))]
      (or (coalesce perm-type perm-values)
          (least-permissive-value perm-type)))))

(mu/defn native-download-permission-for-user :- PermissionValue
  "Returns the effective download permission value for a given user and database ID, for native queries on the database.
  For each group, the native download permission for a database is equal to the lowest permission level of any table in
  the database."
  [user-id database-id]
  (if (is-superuser? user-id)
    (most-permissive-value :perms/download-results)
    (let [perm-values
          (->> (get-permissions user-id :perms/download-results database-id)
               (map (fn [{:keys [perm_value group_id]}]
                      {:group_id group_id :value perm_value})))

          value-by-group
          (-> (group-by :group_id perm-values)
              (update-vals (fn [perms]
                             (let [values (set (map :value perms))]
                               (coalesce-most-restrictive :perms/download-results values)))))]
      (or (coalesce :perms/download-results (vals value-by-group))
          (least-permissive-value :perms/download-results)))))

(mu/defn user-has-block-perms-for-database? :- :boolean
  "Returns a Boolean indicating whether the given user should have block permissions enforced for the given database.
  This is a standalone function because block perms are only set at the database-level, but :perms/data-access is
  generally checked at the table-level, except in the case of block perms."
  [user-id database-id]
  (if (is-superuser? user-id)
    false
    (let [perm-values
          (->> (get-permissions user-id :perms/data-access database-id)
               (map :perm_value)
               (into #{}))]
      (= (coalesce :perms/data-access perm-values)
         :block))))

(mu/defn user-has-any-perms-of-type? :- :boolean
  "Returns a Boolean indicating whether the user has the highest level of access for the given permission type in any
  group, for at least one database or table."
  [user-id perm-type]
  (or (is-superuser? user-id)
      (let [value (most-permissive-value perm-type)]
        (t2/exists? :model/DataPermissions
                    {:select [[:p.perm_value :value]]
                     :from [[:permissions_group_membership :pgm]]
                     :join [[:permissions_group :pg] [:= :pg.id :pgm.group_id]
                            [:data_permissions :p]   [:= :p.group_id :pg.id]]
                     :where [:and
                             [:= :pgm.user_id user-id]
                             [:= :p.perm_type (u/qualified-name perm-type)]
                             [:= :p.perm_value (u/qualified-name value)]]}))))

(defn- admin-permission-graph
  "Returns the graph representing admin permissions for all groups"
  [& {:keys [db-id perm-type]}]
  (let [db-ids     (if db-id [db-id] (t2/select-pks-vec :model/Database))
        perm-types (if perm-type [perm-type] (keys Permissions))]
    (into {} (map (fn [db-id]
                    [db-id (into {} (map (fn [perm] [perm (most-permissive-value perm)])
                                         perm-types))])
                  db-ids))))

(mu/defn permissions-for-user
  "Returns a graph representing the permissions for a single user. Can be optionally filtered by database ID and/or permission type.
  Combines permissions from multiple groups into a single value for each DB/table and permission type.

  This is intended to be used for logging and debugging purposes, to see what a user's real permissions are at a glance. Enforcement
  should happen via `database-permission-for-user` and `table-permission-for-user`."
  [user-id & {:keys [db-id perm-type]}]
  (if (is-superuser? user-id)
    (admin-permission-graph :db-id db-id :perm-type perm-type)
    (let [data-perms    (t2/select :model/DataPermissions
                                   {:select [[:p.perm_type :perm-type]
                                             [:p.group_id :group-id]
                                             [:p.perm_value :value]
                                             [:p.db_id :db-id]
                                             [:p.table_id :table-id]]
                                    :from [[:permissions_group_membership :pgm]]
                                    :join [[:permissions_group :pg] [:= :pg.id :pgm.group_id]
                                           [:data_permissions :p]   [:= :p.group_id :pg.id]]
                                    :where [:and
                                            [:= :pgm.user_id user-id]
                                            (when db-id [:= :db_id db-id])
                                            (when perm-type [:= :perm_type (u/qualified-name perm-type)])]})
          path->perms     (group-by (fn [{:keys [db-id perm-type table-id]}]
                                      (if table-id
                                        [db-id perm-type table-id]
                                        [db-id perm-type]))
                                    data-perms)
          coalesced-perms (reduce-kv
                           (fn [result path perms]
                             ;; Combine permissions from multiple groups into a single value
                             (let [[db-id perm-type] path
                                   coalesced-perms (coalesce perm-type
                                                             (concat
                                                              (map :value perms)
                                                              (map :value (get path->perms [db-id perm-type]))))]
                               (assoc result path coalesced-perms)))
                           {}
                           path->perms)
          granular-graph  (reduce
                           (fn [graph [[db-id perm-type table-id] value]]
                             (let [current-perms (get-in graph [db-id perm-type])
                                   updated-perms (if table-id
                                                   (if (keyword? current-perms)
                                                     {table-id value}
                                                     (assoc current-perms table-id value))
                                                   (if (map? current-perms)
                                                     current-perms
                                                     value))]
                               (assoc-in graph [db-id perm-type] updated-perms)))
                           {}
                           coalesced-perms)]
      (reduce (fn [new-graph [db-id perms]]
                (assoc new-graph db-id
                       (reduce (fn [new-perms [perm-type value]]
                                 (if (and (map? value)
                                          (apply = (vals value)))
                                   (assoc new-perms perm-type (first (vals value)))
                                   (assoc new-perms perm-type value)))
                               {}
                               perms)))
              {}
              granular-graph))))


;;; ---------------------------------------- Fetching the data permissions graph --------------------------------------

(def ^:private Graph
  [:map-of [:int {:title "group-id" :min 0}]
   [:map-of [:int {:title "db-id" :min 0}]
    [:map-of PermissionType
     [:or
      PermissionValue
      [:map-of [:string {:title "schema"}]
       [:map-of
        [:int {:title "table-id" :min 0}]
        PermissionValue]]]]]])

(mu/defn data-permissions-graph :- Graph
  "Returns a tree representation of all data permissions. Can be optionally filtered by group ID, database ID,
  and/or permission type. This is intended to power the permissions editor in the admin panel, and should not be used
  for permission enforcement, as it will read much more data than necessary."
  [& {:keys [group-id db-id perm-type audit?]}]
  (let [data-perms (t2/select [:model/DataPermissions
                               [:perm_type :type]
                               [:group_id :group-id]
                               [:perm_value :value]
                               [:db_id :db-id]
                               [:schema_name :schema]
                               [:table_id :table-id]]
                              {:where [:and
                                       (when perm-type [:= :perm_type (u/qualified-name perm-type)])
                                       (when db-id [:= :db_id db-id])
                                       (when group-id [:= :group_id group-id])
                                       (when-not audit? [:not= :db_id config/audit-db-id])]})]
    (reduce
     (fn [graph {group-id  :group-id
                 perm-type :type
                 value     :value
                 db-id     :db-id
                 schema    :schema
                 table-id  :table-id}]
       (let [schema (or schema "")
             path   (if table-id
                      [group-id db-id perm-type schema table-id]
                      [group-id db-id perm-type])]
         (assoc-in graph path value)))
     {}
     data-perms)))


;;; --------------------------------------------- Updating permissions ------------------------------------------------

(defn- assert-valid-permission
  [{:keys [perm_type perm_value] :as permission}]
  (when-not (mc/validate PermissionType perm_type)
    (throw (ex-info (str/join (mu/explain PermissionType perm_type)) permission)))
  (assert-value-matches-perm-type perm_type perm_value))

(t2/define-before-insert :model/DataPermissions
  [permission]
  (assert-valid-permission permission)
  permission)

(t2/define-before-update :model/DataPermissions
  [permission]
  (assert-valid-permission permission)
  permission)

(def ^:private TheIdable
  "An ID, or something with an ID."
  [:or pos-int? [:map [:id pos-int?]]])

(mu/defn set-database-permission!
  "Sets a single permission to a specified value for a given group and database. If a permission value already exists
  for the specified group and object, it will be updated to the new value.

  Block permissions (i.e. :perms/data-access :block) can only be set at the database-level, despite :perms/data-access
  being a table-level permission."
  [group-or-id :- TheIdable
   db-or-id    :- TheIdable
   perm-type   :- :keyword
   value       :- :keyword]
  (t2/with-transaction [_conn]
    (let [group-id (u/the-id group-or-id)
          db-id    (u/the-id db-or-id)]
      (t2/delete! :model/DataPermissions :perm_type perm-type :group_id group-id :db_id db-id)
      (t2/insert! :model/DataPermissions {:perm_type  perm-type
                                          :group_id   group-id
                                          :perm_value value
                                          :db_id      db-id})
      (when (= [:perms/data-access :block] [perm-type value])
        (set-database-permission! group-or-id db-or-id :perms/native-query-editing :no)
        (set-database-permission! group-or-id db-or-id :perms/download-results :no)))))

(mu/defn set-table-permissions!
  "Sets table permissions to specified values for a given group. If a permission value already exists for a specified
  group and table, it will be updated to the new value.

  `table-perms` is a map from tables or table ID to the permission value for each table. All tables in the list must
  belong to the same database.

  If this permission is currently set at the database-level, the database-level permission
  is removed and table-level rows are are added for all of its tables. Similarly, if setting a table-level permission to a value
  that results in all of the database's tables having the same permission, it is replaced with a single database-level row."
  [group-or-id :- TheIdable
   perm-type   :- :keyword
   table-perms :- [:map-of TheIdable :keyword]]
  (when (not= :model/Table (model-by-perm-type perm-type))
    (throw (ex-info (tru "Permission type {0} cannot be set on tables." perm-type)
                    {perm-type (Permissions perm-type)})))
  (let [values (set (vals table-perms))]
    (when (values :block)
      (throw (ex-info (tru "Block permissions must be set at the database-level only.")
                      {})))
    ;; if `table-perms` is empty, there's nothing to do
    (when (seq table-perms)
      (t2/with-transaction [_conn]
        (let [group-id               (u/the-id group-or-id)
              new-perms              (map (fn [[table value]]
                                            (let [{:keys [id db_id schema]}
                                                  (if (map? table)
                                                    table
                                                    (t2/select-one [:model/Table :id :db_id :schema] :id table))]
                                              {:perm_type   perm-type
                                               :group_id    group-id
                                               :perm_value  value
                                               :db_id       db_id
                                               :table_id    id
                                               :schema_name schema}))
                                          table-perms)
              _                      (when (not= (count (set (map :db_id new-perms))) 1)
                                       (throw (ex-info (tru "All tables must belong to the same database.")
                                                       {:new-perms new-perms})))
              table-ids              (map :table_id new-perms)
              db-id                  (:db_id (first new-perms))
              existing-db-perm       (t2/select-one :model/DataPermissions
                                                    {:where
                                                     [:and
                                                      [:= :perm_type (u/qualified-name perm-type)]
                                                      [:= :group_id  group-id]
                                                      [:= :db_id     db-id]
                                                      [:= :table_id  nil]]})
              existing-db-perm-value (:perm_value existing-db-perm)]
          (if existing-db-perm
            (when (not= values #{existing-db-perm-value})
              ;; If we're setting any table permissions to a value that is different from the database-level permission,
              ;; we need to replace it with individual permission rows for every table in the database instead.
              ;; If the database-level permission was previously `:block`, we set the tables to `:no-self-service`.
              (let [other-tables    (t2/select :model/Table {:where [:and
                                                                     [:= :db_id db-id]
                                                                     [:not [:in :id table-ids]]]})
                    other-new-perms (map (fn [table]
                                           {:perm_type   perm-type
                                            :group_id    group-id
                                            :perm_value  (if (= :block existing-db-perm-value)
                                                           :no-self-service
                                                           existing-db-perm-value)
                                            :db_id       db-id
                                            :table_id    (:id table)
                                            :schema_name (:schema table)})
                                         other-tables)]
                (t2/delete! :model/DataPermissions :id (:id existing-db-perm))
                (t2/insert! :model/DataPermissions (concat other-new-perms new-perms))))
            (let [existing-table-perms (t2/select :model/DataPermissions
                                                  :perm_type (u/qualified-name perm-type)
                                                  :group_id  group-id
                                                  :db_id     db-id
                                                  {:where [:and
                                                           [:not= :table_id nil]
                                                           [:not [:in :table_id table-ids]]]})
                  existing-table-values (set (map :perm_value existing-table-perms))]
              (if (and (= (count existing-table-values) 1)
                       (= values existing-table-values))
                ;; If all tables would have the same permissions after we update these ones, we can replace all of the table
                ;; perms with a DB-level perm instead.
                (set-database-permission! group-or-id db-id perm-type (first values))
                ;; Otherwise, just replace the rows for the individual table perm
                (do
                  (t2/delete! :model/DataPermissions :perm_type perm-type :group_id group-id {:where [:in :table_id table-ids]})
                  (t2/insert! :model/DataPermissions new-perms))))))))))

(mu/defn set-table-permission!
  "Sets permissions for a single table to the specified value for a given group."
  [group-or-id :- TheIdable
   table-or-id :- TheIdable
   perm-type   :- :keyword
   value       :- :keyword]
  (set-table-permissions! group-or-id perm-type {table-or-id value}))

(defn- schema-permission-value
  "Infers the permission value for a new table based on existing permissions in the schema.
  Returns the uniform permission value if one exists, otherwise nil."
  [db-id group-id schema-name perm-type]
  (let [possible-values    (:values (get Permissions perm-type))
        schema-perms-check (mapv (fn [value]
                                   (t2/exists? :model/DataPermissions
                                               :perm_type   (u/qualified-name perm-type)
                                               :db_id       db-id
                                               :group_id    group-id
                                               :schema_name schema-name
                                               :perm_value  value))
                                 possible-values)
        single-perm-val?   (= (count (filter true? schema-perms-check)) 1)]
    (when single-perm-val?
      (nth possible-values (.indexOf ^PersistentVector schema-perms-check true)))))

(mu/defn set-new-table-permissions!
  "Sets permissions for a single table to the specified value in all the provided groups.
  If all tables in the schema have the same permission value, the new table permission is added with that value.
  Otherwise, the new table permission is added with the provided value."
  [groups-or-ids :- [:sequential TheIdable]
   table-or-id   :- TheIdable
   perm-type     :- :keyword
   value         :- :keyword]
  (when (not= :model/Table (model-by-perm-type perm-type))
    (throw (ex-info (tru "Permission type {0} cannot be set on tables." perm-type)
                    {perm-type (Permissions perm-type)})))
  (when (= value :block)
    (throw (ex-info (tru "Block permissions must be set at the database-level only.")
                    {})))
  (when (seq groups-or-ids)
    (t2/with-transaction [_conn]
      (let [group-ids          (map u/the-id groups-or-ids)
            table              (if (map? table-or-id)
                                 table-or-id
                                 (t2/select-one [:model/Table :id :db_id :schema] :id table-or-id))
            db-id              (:db_id table)
            schema-name        (:schema table)
            db-level-perms     (t2/select :model/DataPermissions
                                          :perm_type (u/qualified-name perm-type)
                                          :db_id db-id
                                          :table_id nil
                                          {:where [:in :group_id group-ids]})
            db-level-group-ids (set (map :group_id db-level-perms))
            granular-group-ids (set/difference (set group-ids) db-level-group-ids)
            new-perms          (map (fn [group-id]
                                      {:perm_type   perm-type
                                       :group_id    group-id
                                       :perm_value  (or (schema-permission-value db-id group-id schema-name perm-type)
                                                        value)
                                       :db_id       db-id
                                       :table_id    (u/the-id table)
                                       :schema_name schema-name})
                                    granular-group-ids)]
        (t2/insert! :model/DataPermissions new-perms)))))
