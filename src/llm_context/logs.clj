(ns llm-context.logs
  "Bound project-owned process logs at safe process-start boundaries."
  (:import [java.nio.file Files LinkOption Path StandardCopyOption]))

(def ^:private no-follow-links
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- rotated ^Path [^Path path index]
  (.resolveSibling path (str (.getFileName path) "." index)))

(defn rotate-before-start!
  "Rotate one exact regular log before its writer starts. Never touches a
  symlink or scans arbitrary directory entries."
  [^Path path {:keys [log-max-bytes log-retained-files]}]
  (when (and (Files/isRegularFile path no-follow-links)
             (>= (Files/size path) log-max-bytes))
    (if (zero? log-retained-files)
      (Files/deleteIfExists path)
      (do
        (Files/deleteIfExists (rotated path log-retained-files))
        (doseq [index (range (dec log-retained-files) 0 -1)]
          (let [source (rotated path index)]
            (when (Files/isRegularFile source no-follow-links)
              (Files/move source (rotated path (inc index))
                          (into-array java.nio.file.CopyOption
                                      [StandardCopyOption/REPLACE_EXISTING])))))
        (Files/move path (rotated path 1)
                    (into-array java.nio.file.CopyOption
                                [StandardCopyOption/REPLACE_EXISTING])))))
  nil)
