(ns llm-context.intent-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.intent :as intent]))

(deftest query-plans-distinguish-lookup-set-and-flow
  (is (= :lookup (:shape (intent/analyze "how to reset password"))))
  (is (= :single (:seed-mode (intent/analyze "how to reset password"))))
  (is (= :set (:shape (intent/analyze "what modules expose HTTP endpoints?"))))
  (is (= :multi (:seed-mode (intent/analyze "what modules expose HTTP endpoints?"))))
  (is (= :flow (:shape (intent/analyze "how is email validated before it is sent?"))))
  (is (= 2 (:max-seeds (intent/analyze
                        "how is email validated before it is sent?"))))
  (is (= :lookup (:shape (intent/analyze
                          "what modules expose HTTP endpoints?"
                          {:seed-mode :single}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Seed mode"
                        (intent/normalize-seed-mode "everything"))))

(deftest endpoint-reranking-uses-structural-evidence-without-changing-scores
  (let [query "what modules expose HTTP endpoints?"
        plan (intent/analyze query)
        candidates [{:id "model" :name "type->model"
                     :qualified-name "app.actions/type->model"
                     :file "src/app/actions.clj" :doc "Returns a model."
                     :score 0.031}
                    {:id "safe" :name "safe-url?"
                     :qualified-name "app.http/safe-url?"
                     :file "src/app/http.clj" :doc "Checks an HTTP URL."
                     :score 0.030}
                    {:id "routes" :name "routes"
                     :qualified-name "app.session.api/routes"
                     :file "src/app/session/api.clj" :doc "`/api/session` routes."
                     :score 0.012}]
        result (intent/rerank query candidates plan)]
    (is (= ["routes" "safe" "model"] (mapv :id (:results result))))
    (is (= 0.012 (:score (first (:results result)))))
    (is (true? (:intent-qualified? (first (:results result)))))
    (is (false? (:intent-qualified? (second (:results result)))))
    (is (= :applied (:status result)))
    (is (:reordered? result))))

(deftest multi-seed-selection-is-bounded-and-diverse
  (let [plan (assoc (intent/analyze "list API endpoints") :max-seeds 2)
        candidates [{:id "a" :file "src/a.clj" :qualified-name "app.a/routes"
                     :intent-score 4}
                    {:id "a-helper" :file "src/a.clj" :qualified-name "app.a/handler"
                     :intent-score 3}
                    {:id "b" :file "src/b.clj" :qualified-name "app.b/routes"
                     :intent-score 2}]]
    (is (= ["a" "b"] (mapv :id (intent/select-seeds candidates plan))))))

(deftest endpoint-root-selection-rejects-broad-semantic-matches
  (let [plan (intent/analyze "what modules expose HTTP endpoints?")
        candidates [{:id "server" :file "server.clj"
                     :qualified-name "server/respond"
                     :intent-score 9.0 :intent-qualified? false}
                    {:id "routes" :file "routes.clj"
                     :qualified-name "api/routes"
                     :intent-score 5.0 :intent-qualified? true}]]
    (is (= ["routes"] (mapv :id (intent/select-seeds candidates plan))))))
