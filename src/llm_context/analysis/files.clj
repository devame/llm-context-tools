(ns llm-context.analysis.files
  (:require [clojure.string :as str]
            [llm-context.parser.provider :as parser]
            [llm-context.project :as project])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path]))

(defn- excluded? [relative-path excluded]
  (some (fn [prefix]
          (let [prefix (str/replace prefix #"[/\\]+$" "")]
            (or (= relative-path prefix)
                (str/starts-with? relative-path (str prefix "/"))
                (and (not (str/includes? prefix "/"))
                     (contains? (set (str/split relative-path #"/")) prefix)))))
        excluded))

(defn- walk-files [^Path path]
  (with-open [stream (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
    (vec (iterator-seq (.iterator stream)))))

(defn- binary? [bytes]
  (some zero? (take (min 8192 (alength ^bytes bytes)) bytes)))

(defn discover
  "Discover supported source files without following symlinked directories.
  Return analyzable file inputs and explicit skip diagnostics."
  [project config supported-languages]
  (let [^Path root (:root project)
        includes (get-in config [:analysis :include])
        excludes (set (get-in config [:analysis :exclude]))
        max-bytes (get-in config [:analysis :max-file-bytes])
        candidates (->> includes
                        (map #(.resolve root ^String %))
                        (filter #(Files/exists ^Path % (make-array LinkOption 0)))
                        (mapcat walk-files)
                        distinct
                        (filter #(Files/isRegularFile ^Path % (make-array LinkOption 0)))
                        (sort-by str))]
    (reduce
     (fn [{:keys [files diagnostics] :as result} ^Path path]
       (let [relative (project/relative-path project path)
             language (parser/language-for-path relative)
             size (Files/size path)]
         (cond
           (excluded? relative excludes) result
           (nil? language)
           (update result :diagnostics conj
                   {:level :info :kind :unsupported-extension :file relative})
           (not (contains? supported-languages language))
           (update result :diagnostics conj
                   {:level :warning :kind :grammar-unavailable
                    :file relative :language language})
           (> size max-bytes)
           (update result :diagnostics conj
                   {:level :warning :kind :file-too-large :file relative :size size})
           :else
           (let [bytes (Files/readAllBytes path)]
             (if (binary? bytes)
               (update result :diagnostics conj
                       {:level :warning :kind :binary-file :file relative})
               (update result :files conj
                       {:path path
                        :relative-path relative
                        :language language
                        :content (String. bytes StandardCharsets/UTF_8)
                        :size size
                        :modified-at (.toMillis (Files/getLastModifiedTime path
                                                  (make-array LinkOption 0)))}))))))
     {:files [] :diagnostics []}
     candidates)))
