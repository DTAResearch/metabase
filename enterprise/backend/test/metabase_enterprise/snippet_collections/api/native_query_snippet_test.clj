(ns metabase-enterprise.snippet-collections.api.native-query-snippet-test
  (:require
   [clojure.test :refer :all]
   [metabase.collections.models.collection :as collection]
   [metabase.permissions.models.data-permissions :as data-perms]
   [metabase.permissions.models.permissions :as perms]
   [metabase.permissions.models.permissions-group :as perms-group]
   [metabase.test :as mt]
   [metabase.util :as u]
   [toucan2.core :as t2]))

(def ^:private root-collection (assoc collection/root-collection :name "Root Collection", :namespace "snippets"))

(defn- test-perms!
  "Test whether we have permissions to see/edit/etc. a Snippet by calling `(has-perms? snippet)`. `required-perms` is
  the permissions we *should* need, either `:read` or `:write`."
  [required-perms has-perms?]
  (mt/with-non-admin-groups-no-root-collection-for-namespace-perms "snippets"
    (mt/with-temp [:model/Collection      normal-collection {:name "Normal Collection", :namespace "snippets"}]
      ;; A user needs native query permissions on *any* database (among other things, in EE) to read/edit/create a NativeQuerySnippet
      (data-perms/set-database-permission! (perms-group/all-users) (mt/id) :perms/create-queries :query-builder-and-native)
      ;; run tests with both a normal Collection and the Root Collection
      (doseq [{collection-name :name, :as collection} [normal-collection root-collection]]
        (testing (format "\nSnippet in %s" collection-name)
          (mt/with-temp [:model/NativeQuerySnippet snippet {:collection_id (:id collection)}]
            (testing "\nShould be allowed regardless if EE features aren't enabled"
              (mt/with-premium-features #{}
                (is (true?
                     (has-perms? snippet))
                    "allowed?")))
            (testing "\nWith EE features enabled"
              (mt/with-premium-features #{:snippet-collections}
                (testing (format "\nShould not be allowed with no perms for %s" collection-name)
                  (is (= false
                         (has-perms? snippet))
                      "allowed?"))
                (perms/grant-collection-read-permissions! (perms-group/all-users) collection)
                (testing (format "\nShould %s allowed if we have read perms for %s"
                                 (case required-perms :read "be" :write "NOT be")
                                 collection-name)
                  (is (= (case required-perms
                           :read  true
                           :write false)
                         (has-perms? snippet))
                      "allowed?"))
                (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
                (testing (format "\nShould be allowed if we have write perms for %s" collection-name)
                  (is (true?
                       (has-perms? snippet))
                      "allowed?"))))))))))

(deftest list-test
  (testing "GET /api/native-query-snippet"
    (testing "\nShould only see Snippet if you have parent Collection perms"
      (test-perms!
       :read
       (fn [snippet]
         (boolean
          (some
           (fn [a-snippet]
             (= (u/the-id a-snippet) (u/the-id snippet)))
           (mt/user-http-request :rasta :get "native-query-snippet"))))))))

(deftest fetch-test
  (testing "GET /api/native-query-snippet/:id"
    (testing "\nShould only be able to fetch Snippet if you have parent Collection perms"
      (test-perms!
       :read
       (fn [snippet]
         (let [response (mt/user-http-request :rasta :get (format "native-query-snippet/%d" (u/the-id snippet)))]
           (not= response "You don't have permissions to do that.")))))))

(deftest create-test
  (testing "POST /api/native-query-snippet"
    (testing "\nShould require parent Collection perms to create a new Snippet in that Collection"
      (test-perms!
       :write
       (fn [snippet]
         ;; try creating a copy of the Snippet, but with a different name and with `:id` removed
         (let [snippet-name       (mt/random-name)
               snippet-properties (-> snippet (assoc :name snippet-name) (dissoc :id))]
           (try
             (let [response (mt/user-http-request :rasta :post "native-query-snippet" snippet-properties)]
               (not= response "You don't have permissions to do that."))
             (finally
               (t2/delete! :model/NativeQuerySnippet :name snippet-name)))))))))

(deftest edit-test
  (testing "PUT /api/native-query-snippet/:id"
    (testing "\nShould require parent Collection perms to edit a Snippet"
      (test-perms!
       :write
       (fn [snippet]
         (let [response (mt/user-http-request :rasta :put (format "native-query-snippet/%d" (u/the-id snippet)) {:name (mt/random-name)})]
           (not= response "You don't have permissions to do that.")))))))

(deftest move-perms-test
  (testing "PUT /api/native-query-snippet/:id"
    (testing "\nPerms for moving a Snippet"
      (mt/with-non-admin-groups-no-root-collection-for-namespace-perms "snippets"
        (mt/with-temp [:model/Collection source {:name "Current Parent Collection", :namespace "snippets"}
                       :model/Collection dest   {:name "New Parent Collection", :namespace "snippets"}]
          ;; A user needs native query permissions on *any* database (among other things, in EE) to read/edit/create a NativeQuerySnippet
          (data-perms/set-database-permission! (perms-group/all-users) (mt/id) :perms/create-queries :query-builder-and-native)
          (doseq [source-collection [source root-collection]]
            (mt/with-temp [:model/NativeQuerySnippet snippet {:collection_id (:id source-collection)}]
              (doseq [dest-collection [dest root-collection]]
                (letfn [(has-perms? []
                          ;; make sure the Snippet is back in the original Collection if it was changed
                          (t2/update! :model/NativeQuerySnippet (:id snippet) {:collection_id (:id source-collection)})
                          (let [response (mt/user-http-request :rasta :put (format "native-query-snippet/%d" (:id snippet))
                                                               {:collection_id (:id dest-collection)})]
                            (cond
                              (= response "You don't have permissions to do that.")                     false
                              (and (map? response) (= (:collection_id response) (:id dest-collection))) true
                              :else                                                                     response)))]
                  (when-not (= source-collection dest-collection)
                    (testing (format "\nMove from %s -> %s should need write ('curate') perms for both" (:name source-collection) (:name dest-collection))
                      (testing "\nShould be allowed if EE perms aren't enabled"
                        (mt/with-premium-features #{}
                          (is (true?
                               (has-perms?)))))
                      (mt/with-premium-features #{:snippet-collections}
                        (doseq [c [source-collection dest-collection]]
                          (testing (format "\nPerms for only %s should fail" (:name c))
                            (try
                              (perms/grant-collection-readwrite-permissions! (perms-group/all-users) c)
                              (is (= false
                                     (has-perms?)))
                              (finally
                                (perms/revoke-collection-permissions! (perms-group/all-users) c)))))
                        (testing "\nShould succeed with both"
                          (try
                            (doseq [c [source-collection dest-collection]]
                              (perms/grant-collection-readwrite-permissions! (perms-group/all-users) c))
                            (is (true?
                                 (has-perms?)))
                            (finally
                              (doseq [c [source-collection dest-collection]]
                                (perms/revoke-collection-permissions! (perms-group/all-users) c)))))))))))))))))

(deftest ^:parallel snippet-collection-items-test
  (testing "GET /api/collection/:id/items"
    (testing "Native query snippets should come back when fetching the items in a Collection in the `:snippets` namespace"
      (mt/with-temp [:model/Collection         collection {:namespace "snippets", :name "My Snippet Collection"}
                     :model/NativeQuerySnippet snippet    {:collection_id (:id collection), :name "My Snippet"}
                     :model/NativeQuerySnippet archived   {:collection_id (:id collection) , :name "Archived Snippet", :archived true}]
        (mt/with-premium-features #{:snippet-collections}
          (is (=? [{:id        (:id snippet)
                    :name      "My Snippet"
                    :entity_id (:entity_id snippet)
                    :model     "snippet"}]
                  (:data (mt/user-http-request :rasta :get 200 (format "collection/%d/items" (:id collection))))))
          (testing "\nShould be able to fetch archived Snippets"
            (is (=? [{:id        (:id archived)
                      :name      "Archived Snippet"
                      :entity_id (:entity_id archived)
                      :model     "snippet"}]
                    (:data (mt/user-http-request :rasta :get 200 (format "collection/%d/items?archived=true" (:id collection)))))))
          (testing "\nShould be able to pass ?model=snippet, even though it makes no difference in this case"
            (is (=? [{:id        (:id snippet)
                      :name      "My Snippet"
                      :entity_id (:entity_id snippet)
                      :model     "snippet"}]
                    (:data (mt/user-http-request :rasta :get 200 (format "collection/%d/items?model=snippet" (:id collection))))))))))))

(deftest snippet-collection-items-test-2
  (testing "GET /api/collection/:id/items"
    (testing "Snippet collections should be returned on EE with the snippet-collections feature flag, rather than
             returning all nested snippets as a flat list"
      (mt/with-premium-features #{:snippet-collections}
        (mt/with-temp [:model/Collection         collection {:namespace "snippets" :name "My Snippet Collection"}
                       :model/Collection         sub-collection {:namespace "snippets"
                                                                 :name      "Nested Snippet Collection"
                                                                 :location  (collection/location-path collection)}
                       :model/NativeQuerySnippet snippet {:collection_id (:id collection) :name "My Snippet"}
                       :model/NativeQuerySnippet _ {:collection_id (:id sub-collection)
                                                    :name          "Nested Snippet"}]
          (is (=?
               [{:id (:id snippet) :name "My Snippet"}
                {:id (:id sub-collection) :name "Nested Snippet Collection"}]
               (:data (mt/user-http-request :rasta :get 200 (format "collection/%d/items" (:id collection)))))))))))

(deftest ee-disabled-snippets-graph-test
  (testing "GET /api/collection/root/items?namespace=snippets"
    (mt/with-non-admin-groups-no-root-collection-for-namespace-perms "snippets"
      (mt/with-temp [:model/NativeQuerySnippet snippet]
        (letfn [(can-see-snippet? []
                  (let [response (:data (mt/user-http-request :rasta :get "collection/root/items?namespace=snippets"))]
                    (boolean (some (fn [a-snippet]
                                     (= (:id snippet) (:id a-snippet)))
                                   response))))]
          (testing "\nIf we have a valid EE token, we should only see Snippets in the Root Collection with valid perms"
            (mt/with-premium-features #{:snippet-collections}
              (is (false? (can-see-snippet?)))
              (perms/grant-collection-read-permissions! (perms-group/all-users) (assoc collection/root-collection :namespace "snippets"))
              (is (true? (can-see-snippet?)))))
          (testing "\nIf we do not have a valid EE token, all Snippets should come back from the graph regardless of our perms"
            (mt/with-premium-features #{}
              (is (true? (can-see-snippet?)))
              (perms/revoke-collection-permissions! (perms-group/all-users) (assoc collection/root-collection :namespace "snippets"))
              (is (true? (can-see-snippet?))))))))))
