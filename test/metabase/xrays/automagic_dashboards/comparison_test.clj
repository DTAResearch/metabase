(ns metabase.xrays.automagic-dashboards.comparison-test
  (:require
   [clojure.test :refer :all]
   [metabase.test :as mt]
   [metabase.xrays.api.automagic-dashboards :as api.automagic-dashboards]
   [metabase.xrays.automagic-dashboards.comparison :as c]
   [metabase.xrays.automagic-dashboards.core :as magic]
   [metabase.xrays.test-util.automagic-dashboards :refer [with-dashboard-cleanup!]]
   [toucan2.core :as t2]))

(def ^:private segment
  (delay
    {:table_id   (mt/id :venues)
     :definition {:filter [:> [:field (mt/id :venues :price) nil] 10]}}))

(defn- test-comparison
  [left right]
  (-> left
      (magic/automagic-analysis {})
      (c/comparison-dashboard left right {})
      :dashcards
      count
      pos?))

;; TODO -- I don't know what these are supposed to test. So I have no idea what to name them.

(deftest test-1
  (mt/with-temp [:model/Segment {segment-id :id} @segment]
    (mt/with-test-user :rasta
      (with-dashboard-cleanup!
        (is (some? (test-comparison (t2/select-one :model/Table :id (mt/id :venues)) (t2/select-one :model/Segment :id segment-id))))
        (is (some? (test-comparison (t2/select-one :model/Segment :id segment-id) (t2/select-one :model/Table :id (mt/id :venues)))))))))

(deftest test-2
  (mt/with-temp [:model/Segment {segment1-id :id} @segment
                 :model/Segment {segment2-id :id} {:table_id   (mt/id :venues)
                                                   :definition {:filter [:< [:field (mt/id :venues :price) nil] 4]}}]
    (mt/with-test-user :rasta
      (with-dashboard-cleanup!
        (is (some? (test-comparison (t2/select-one :model/Segment :id segment1-id) (t2/select-one :model/Segment :id segment2-id))))))))

(deftest test-3
  (mt/with-test-user :rasta
    (with-dashboard-cleanup!
      (let [q (api.automagic-dashboards/adhoc-query-instance {:query    {:filter       (-> @segment :definition :filter)
                                                                         :source-table (mt/id :venues)}
                                                              :type     :query
                                                              :database (mt/id)})]
        (is (some? (test-comparison (t2/select-one :model/Table :id (mt/id :venues)) q)))))))

(deftest test-4
  (mt/with-temp [:model/Card {card-id :id} {:table_id      (mt/id :venues)
                                            :dataset_query {:query    {:filter       (-> @segment :definition :filter)
                                                                       :source-table (mt/id :venues)}
                                                            :type     :query
                                                            :database (mt/id)}}]
    (mt/with-test-user :rasta
      (with-dashboard-cleanup!
        (is (some? (test-comparison (t2/select-one :model/Table :id (mt/id :venues)) (t2/select-one :model/Card :id card-id))))))))
