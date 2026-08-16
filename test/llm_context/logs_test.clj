(ns llm-context.logs-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.logs :as logs])
  (:import [java.nio.file Files OpenOption]))

(deftest process-start-rotation-is-size-bounded-and-retained
  (let [root (Files/createTempDirectory
              "llm-context-logs-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        path (.resolve root "service.log")
        settings {:log-max-bytes 5 :log-retained-files 2}
        write! #(Files/writeString path % (make-array OpenOption 0))]
    (write! "first!")
    (logs/rotate-before-start! path settings)
    (is (= "first!" (Files/readString (.resolve root "service.log.1"))))
    (write! "second")
    (logs/rotate-before-start! path settings)
    (is (= "second" (Files/readString (.resolve root "service.log.1"))))
    (is (= "first!" (Files/readString (.resolve root "service.log.2"))))
    (write! "third!")
    (logs/rotate-before-start! path settings)
    (is (= "third!" (Files/readString (.resolve root "service.log.1"))))
    (is (= "second" (Files/readString (.resolve root "service.log.2"))))
    (is (not (Files/exists path (make-array java.nio.file.LinkOption 0))))))
