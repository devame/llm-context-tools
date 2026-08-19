(ns llm-context.semantic.ingestion-plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.semantic.ingestion-plan :as ingestion-plan]))

(def base-input
  {:pending-symbol-jobs 4096
   :pending-provider-documents-estimate 4096
   :operation-mix {:upserts 4096 :deletes 0}
   :accelerator :cuda
   :provider-version "1.7.0"
   :configured-request-batch 32
   :configured-request-concurrency 1
   :configured-max-inflight-documents 32
   :profile :steady
   :previous-profile :steady
   :cold-ingestion
   {:enabled false
    :backlog-threshold 2048
    :exit-threshold 1024
    :update-batch-size 512
    :update-concurrency 2
    :max-inflight-provider-documents 1024}})

(deftest steady-plan-is-default-equivalent
  (is (= {:profile :steady
          :lease-symbol-limit 32
          :request-provider-document-limit 32
          :request-concurrency-limit 1
          :max-inflight-provider-documents 32
          :accelerator :cuda
          :pending-symbol-jobs 4096
          :pending-provider-documents-estimate 4096
          :reason :configured-steady}
         (ingestion-plan/plan base-input))))

(deftest cold-and-auto-require-explicit-qualification-settings
  (testing "disabled cold mode falls back without changing bounds"
    (is (= :cold-disabled
           (:reason (ingestion-plan/plan (assoc base-input :profile :cold))))))
  (let [enabled (assoc-in base-input [:cold-ingestion :enabled] true)]
    (testing "explicit cold mode uses its independently bounded request plan"
      (is (= {:profile :cold
              :lease-symbol-limit 1024
              :request-provider-document-limit 512
              :request-concurrency-limit 2
              :max-inflight-provider-documents 1024
              :accelerator :cuda
              :pending-symbol-jobs 4096
              :pending-provider-documents-estimate 4096
              :reason :configured-cold}
             (ingestion-plan/plan (assoc enabled :profile :cold)))))
    (testing "auto mode uses hysteresis when a cold backlog is draining"
      (let [draining (assoc enabled
                            :profile :auto
                            :previous-profile :cold
                            :pending-symbol-jobs 1500
                            :pending-provider-documents-estimate 1500)]
        (is (= :cold (:profile (ingestion-plan/plan draining))))
        (is (= :auto-cold-hysteresis
               (:reason (ingestion-plan/plan draining))))
        (is (= :steady
               (:profile
                (ingestion-plan/plan
                 (assoc draining
                        :pending-symbol-jobs 1024
                        :pending-provider-documents-estimate 1024)))))))))

(deftest delete-heavy-and-unqualified-provider-workloads-stay-steady
  (let [enabled (-> base-input
                    (assoc :profile :auto)
                    (assoc-in [:cold-ingestion :enabled] true))]
    (is (= :delete-heavy-workload
           (:reason
            (ingestion-plan/plan
             (assoc enabled :operation-mix {:upserts 700 :deletes 300})))))
    (is (= :provider-version-unqualified
           (:reason
            (ingestion-plan/plan
             (assoc enabled :provider-version "1.8.0")))))))

(deftest configured-inflight-bound-caps-request-concurrency
  (let [planned
        (ingestion-plan/plan
         (assoc base-input
                :configured-request-concurrency 8
                :configured-max-inflight-documents 64))]
    (is (= 2 (:request-concurrency-limit planned)))
    (is (<= (* (:request-provider-document-limit planned)
               (:request-concurrency-limit planned))
            (:max-inflight-provider-documents planned)))))
