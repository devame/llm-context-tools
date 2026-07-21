(ns llm-context.integrations-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.integrations :as integrations]
            [llm-context.project :as project])
  (:import [java.nio.file Files]))

(deftest integrations-are-explicit-and-non-destructive
  (let [root (Files/createTempDirectory "llm-context-integration-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        path (integrations/install! project :claude false)]
    (is (Files/exists path (make-array java.nio.file.LinkOption 0)))
    (is (re-find #"Datalevin" (slurp (str path))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists"
                          (integrations/install! project :claude false)))
    (is (= path (integrations/install! project :claude true)))))
