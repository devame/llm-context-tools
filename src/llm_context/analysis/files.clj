(ns llm-context.analysis.files
  (:require [clojure.string :as str]
            [llm-context.parser.provider :as parser]
            [llm-context.project :as project]
            [llm-context.source :as source])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitResult Files LinkOption Path SimpleFileVisitor]
           [java.nio.file.attribute BasicFileAttributes]))

(defn- excluded? [relative-path excluded]
  (some (fn [prefix]
          (let [prefix (str/replace prefix #"[/\\]+$" "")]
            (or (= relative-path prefix)
                (str/starts-with? relative-path (str prefix "/"))
                (and (not (str/includes? prefix "/"))
                     (contains? (set (str/split relative-path #"/")) prefix)))))
        excluded))

(defn- walk-files
  "Walk one include root while pruning excluded directories before descent."
  [project ^Path include-root excluded]
  (let [found (transient [])
        visitor
        (proxy [SimpleFileVisitor] []
          (preVisitDirectory [^Path directory ^BasicFileAttributes _]
            (let [relative (project/relative-path project directory)]
              (if (and (not= directory include-root)
                       (excluded? relative excluded))
                FileVisitResult/SKIP_SUBTREE
                FileVisitResult/CONTINUE)))
          (visitFile [^Path file ^BasicFileAttributes attributes]
            (when (.isRegularFile attributes)
              (conj! found file))
            FileVisitResult/CONTINUE))]
    (Files/walkFileTree include-root visitor)
    (persistent! found)))

(defn- binary? [bytes]
  (some zero? (take (min 8192 (alength ^bytes bytes)) bytes)))

(defn- git-files
  "Return tracked and non-ignored project files, or nil when Git is not
  available/applicable so discovery can fall back to the filesystem walk."
  [project]
  (let [^Path root (:root project)
        git-marker (.resolve root ".git")]
    (when (Files/exists git-marker (make-array LinkOption 0))
      (try
        (let [process (-> (ProcessBuilder.
                           ^java.util.List
                           ["git" "-C" (:root-str project) "ls-files" "-z"
                            "--cached" "--others" "--exclude-standard"])
                          (.redirectErrorStream true)
                          .start)
              output (String. (.readAllBytes (.getInputStream process))
                              StandardCharsets/UTF_8)
              exit (.waitFor process)]
          (when (zero? exit)
            (->> (str/split output #"\u0000")
                 (remove str/blank?)
                 (mapv #(.normalize (.resolve root ^String %))))))
        (catch Throwable _ nil)))))

(defn- in-includes? [project path includes]
  (let [relative (project/relative-path project path)]
    (some (fn [include]
            (let [prefix (-> include
                             (str/replace #"^\./" "")
                             (str/replace #"[/\\]+$" ""))]
              (or (contains? #{"" "."} prefix)
                  (= relative prefix)
                  (str/starts-with? relative (str prefix "/")))))
          includes)))

(defn discover
  "Discover supported source files without following symlinked directories.
  Return analyzable file inputs and explicit skip diagnostics."
  [project config supported-languages]
  (let [^Path root (:root project)
        includes (get-in config [:analysis :include])
        excludes (set (get-in config [:analysis :exclude]))
        max-bytes (get-in config [:analysis :max-file-bytes])
        include-paths (mapv (fn [include]
                              [include (.normalize (.resolve root ^String include))])
                            includes)
        missing (for [[include ^Path path] include-paths
                      :when (not (Files/exists path (make-array LinkOption 0)))]
                  {:level :warning :kind :missing-include :path include})
        repository-files (git-files project)
        candidates (->> (if (some? repository-files)
                          (filter #(in-includes? project % includes) repository-files)
                          (->> include-paths
                               (keep (fn [[_ ^Path path]]
                                       (when (Files/exists path
                                                           (make-array LinkOption 0))
                                         path)))
                               (mapcat #(walk-files project % excludes))))
                        distinct
                        (filter #(Files/isRegularFile ^Path % (make-array LinkOption 0)))
                        (sort-by str))]
    (reduce
     (fn [result ^Path path]
       (let [relative (project/relative-path project path)]
         (if (excluded? relative excludes)
           result
           (let [language (parser/language-for-path relative)
                 size (Files/size path)]
             (cond
               ;; Whole-project discovery also sees documentation, assets, and
               ;; configuration. Unknown extensions are outside the source
               ;; language contract, not analysis failures.
               (nil? language) result

               (not (contains? supported-languages language))
               (update result :diagnostics conj
                       {:level :warning :kind :grammar-unavailable
                        :file relative :language language})

               (> size max-bytes)
               (update result :diagnostics conj
                       {:level :warning :kind :file-too-large
                        :file relative :size size})

               :else
               (let [bytes (Files/readAllBytes path)]
                 (if (binary? bytes)
                   (update result :diagnostics conj
                           {:level :warning :kind :binary-file :file relative})
                   (let [{:keys [content malformed? malformed-offset]}
                         (source/decode-utf8 bytes)]
                     (cond->
                      (update result :files conj
                              {:path path
                               :relative-path relative
                               :language language
                               :content content
                               :size size
                               :modified-at
                               (.toMillis
                                (Files/getLastModifiedTime
                                 path (make-array LinkOption 0)))})
                       malformed?
                       (update :diagnostics conj
                               {:level :warning
                                :kind :invalid-utf8
                                :file relative
                                :byte-offset malformed-offset
                                :message
                                (str "Malformed UTF-8 was replaced with "
                                     "U+FFFD for deterministic analysis")}))))))))))
     {:files [] :diagnostics (vec missing)}
     candidates)))
