(ns llm-context.intent.reranker-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.intent.reranker :as reranker]))

(defn- embedding [& values]
  {:tokens 1 :dimension (count values) :values (float-array values)})

(def model-settings
  {:model "mixedbread-ai/mxbai-edge-colbert-v0-32m"
   :model-revision "bb13a29ec9b1e7edd4ba8f7a0776c48b55cbad66"})

(def reranker-settings
  {:mode :enforce :candidate-count 2
   :query-timeout-ms 2000 :document-cache-size 8})

(deftest learned-max-sim-reorders-only-the-bounded-prefix-and-caches-documents
  (let [calls (atom [])
        encode-fn
        (fn [texts options]
          (swap! calls conj {:texts texts :options options})
          (case (:input-type options)
            :query [(embedding 1.0 0.0)]
            :document (mapv #(if (re-find #"qualified-name: app/b" %)
                               (embedding 1.0 0.0)
                               (embedding 0.0 1.0))
                            texts)))
        provider (reranker/create-with-encoder
                  encode-fn model-settings reranker-settings)
        candidates [{:id "a" :qualified-name "app/a"}
                    {:id "b" :qualified-name "app/b"}
                    {:id "c" :qualified-name "app/c"}]
        first-result (reranker/safely-rerank
                      provider "Which implementation is relevant?" candidates)
        second-result (reranker/safely-rerank
                       provider "Which implementation is relevant?" candidates)]
    (is (= ["b" "a" "c"] (mapv :id (:results first-result))))
    (is (= 2 (:candidate-count first-result)))
    (is (= 0 (:cache-hits first-result)))
    (is (= 2 (:cache-misses first-result)))
    (is (= 2 (:cache-hits second-result)))
    (is (zero? (:cache-misses second-result)))
    (is (= 1.0 (:learned-score (first (:results first-result)))))
    (is (= "Which implementation is relevant?"
           (get-in @calls [0 :texts 0])))
    (is (= 2 (count (filter #(= :query (get-in % [:options :input-type]))
                            @calls))))
    (is (= 1 (count (filter #(= :document
                                (get-in % [:options :input-type]))
                            @calls))))))

(deftest shadow-mode-exposes-scores-without-changing-order
  (let [encode-fn (fn [_ {:keys [input-type]}]
                    (if (= :query input-type)
                      [(embedding 1.0 0.0)]
                      [(embedding 0.0 1.0) (embedding 1.0 0.0)]))
        provider (reranker/create-with-encoder
                  encode-fn model-settings (assoc reranker-settings
                                                  :mode :shadow))
        result (reranker/safely-rerank
                provider "question" [{:id "a"} {:id "b"}])]
    (is (= ["a" "b"] (mapv :id (:results result))))
    (is (= :shadowed (:status result)))
    (is (:would-reorder? result))
    (is (false? (:reordered? result)))
    (is (every? number? (map :learned-score (:results result))))))

(deftest provider-failure-and-identity-mismatch-preserve-input-order
  (let [candidates [{:id "a"} {:id "b"}]
        failed
        (reify reranker/CandidateReranker
          (rerank [_ _ _] (throw (ex-info "encoder failed" {}))))
        invalid
        (reify reranker/CandidateReranker
          (rerank [_ _ _]
            {:provider :fixture :status :applied
             :results [{:id "unknown"} {:id "a"}]}))]
    (is (= ["a" "b"]
           (mapv :id (:results
                      (reranker/safely-rerank failed "question" candidates)))))
    (is (= :failed
           (:status (reranker/safely-rerank failed "question" candidates))))
    (let [result (reranker/safely-rerank invalid "question" candidates)]
      (is (= ["a" "b"] (mapv :id (:results result))))
      (is (= :identity-mismatch (:reason result))))))

(deftest timeout-and-changed-documents-are-visible
  (let [document-calls (atom 0)
        encode-fn
        (fn [texts {:keys [input-type]}]
          (if (= :query input-type)
            [(embedding 1.0 0.0)]
            (do
              (swap! document-calls + (count texts))
              (mapv (fn [_] (embedding 1.0 0.0)) texts))))
        provider (reranker/create-with-encoder
                  encode-fn model-settings reranker-settings)]
    (reranker/safely-rerank provider "question"
                            [{:id "a" :doc "first"}])
    (reranker/safely-rerank provider "question"
                            [{:id "a" :doc "changed"}])
    (is (= 2 @document-calls)))
  (let [timed-out
        (reify reranker/CandidateReranker
          (rerank [_ _ _]
            (throw (ex-info "request timed out"
                            {:type :next-plaid/transport-error
                             :timeout? true}))))
        result (reranker/safely-rerank timed-out "question" [{:id "a"}])]
    (is (= :timed-out (:status result)))
    (is (= ["a"] (mapv :id (:results result))))))
