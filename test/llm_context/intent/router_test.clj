(ns llm-context.intent.router-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.intent.router :as router]
            [llm-context.semantic.index :as index]))

(deftest next-plaid-router-reports-all-scores-and-margin
  (let [client
        (reify index/SemanticIndex
          (index-health [_] {:ready? true})
          (ensure-index! [_] nil)
          (add-documents! [_ _] nil)
          (delete-symbols! [_ _] nil)
          (indexed-documents [_ _] [])
          (indexed-chunk-count [_ _ _] 0)
          (search-text [_ query options]
            (is (= "trace the request" query))
            (is (= 3 (:top-k options)))
            [{:score 8.4 :metadata {:llm_symbol_id "query-shape/flow"}}
             {:score 8.1 :metadata {:llm_symbol_id "query-shape/set"}}
             {:score 7.9 :metadata {:llm_symbol_id "query-shape/lookup"}}])
          (close-index! [_] nil))
        settings {:model "mixedbread-ai/mxbai-edge-colbert-v0-32m"
                  :model-revision "revision" :query-timeout-ms 250}
        result (router/classify
                (router/->NextPlaidRouter client nil settings)
                "trace the request")]
    (is (= :flow (:suggested-shape result)))
    (is (= {:flow 8.4 :set 8.1 :lookup 7.9} (:scores result)))
    (is (< 0.29 (:margin result) 0.31))
    (is (= :available (:status result)))))
