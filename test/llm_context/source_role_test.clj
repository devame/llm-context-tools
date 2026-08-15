(ns llm-context.source-role-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.source-role :as source-role]))

(deftest repository-relative-paths-have-deterministic-source-roles
  (testing "common test conventions"
    (doseq [file ["test/metabase/session/api_test.clj"
                  "enterprise/backend/test/metabase/api.clj"
                  "src/widget_test.clj"
                  "src/widget.test.ts"
                  "spec/widget_spec.rb"
                  "src\\__tests__\\widget.ts"]]
      (is (= :test (source-role/classify file)) file)))
  (testing "other common roles"
    (is (= :production (source-role/classify "src/contest/runner.clj")))
    (is (= :production (source-role/classify "src/specification.clj")))
    (is (= :generated (source-role/classify "src/generated/client.ts")))
    (is (= :vendor (source-role/classify "third_party/library/core.c")))
    (is (= :vendor (source-role/classify "vendor/library/test/core_test.rb")))
    (is (= :generated (source-role/classify "generated/tests/client_test.ts")))
    (is (= :unknown (source-role/classify nil))))
  (testing "ordered project overrides precede defaults"
    (is (= :production
           (source-role/classify
            "test/support/runtime.clj"
            [{:role :production :pattern "test/support/**"}])))
    (is (= :test
           (source-role/classify
            "quality/auth/check.clj"
            [{:role :test :pattern "quality/**"}])))
    (is (= :test
           (source-role/classify
            "widget_test.clj"
            [{:role :test :pattern "**/*_test.*"}])))))

(deftest automatic-preference-is-narrow-and-inspectable
  (is (= {:requested :auto :resolved :production
          :reason :general-implementation-query}
         (source-role/resolve-preference :auto "how is authentication handled?")))
  (is (= :test
         (:resolved
          (source-role/resolve-preference :auto "which tests cover authentication?"))))
  (is (= :production
         (:resolved
          (source-role/resolve-preference :auto "how does contest scoring work?"))))
  (is (= :none (:resolved (source-role/resolve-preference :none "tests"))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Source preference"
       (source-role/normalize-preference "unknown"))))

(deftest source-preference-is-stable-score-preserving-and-exact-safe
  (let [results [{:id "test" :name "reset-password-test"
                  :qualified-name "app-test/reset-password-test"
                  :file "test/app_test.clj" :score 0.0311}
                 {:id "prod" :name "reset-password"
                  :qualified-name "app/reset-password"
                  :file "src/app.clj" :score 0.0310}
                 {:id "generated" :name "reset-password-schema"
                  :qualified-name "generated/reset-password-schema"
                  :file "src/generated/schema.clj" :score 0.02}]
        preferred (source-role/prefer results "how to reset password"
                                      :production [])]
    (is (= ["prod" "test" "generated"]
           (mapv :id (:results preferred))))
    (is (= [0.0310 0.0311 0.02]
           (mapv :score (:results preferred))))
    (is (= :production-preferred
           (:ranking-reason (first (:results preferred)))))
    (is (= {:test 1 :production 1 :generated 1} (:role-counts preferred)))
    (is (:reordered? preferred))
    (is (= ["test" "prod" "generated"]
           (mapv :id (:results
                     (source-role/prefer results "reset-password-test"
                                         :production [])))))
    (is (= ["test" "prod" "generated"]
           (mapv :id (:results
                     (source-role/prefer results "anything" :none [])))))))
