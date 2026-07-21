(ns llm-context.parser.jtreesitter
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [llm-context.parser.native-lookup :as native-lookup]
            [llm-context.parser.provider :as provider])
  (:import [io.github.treesitter.jtreesitter Language Node Parser]
           [java.io Closeable]
           [java.lang.foreign Arena SymbolLookup]
           [java.nio.file CopyOption Files LinkOption Path StandardCopyOption]))

(def language-definitions
  {:language/javascript {:library "tree-sitter-javascript"
                         :symbol "tree_sitter_javascript"}
   :language/typescript {:library "tree-sitter-typescript"
                         :symbol "tree_sitter_typescript"}
   :language/python {:library "tree-sitter-python"
                     :symbol "tree_sitter_python"}
   :language/java {:library "tree-sitter-java"
                   :symbol "tree_sitter_java"}
   :language/go {:library "tree-sitter-go"
                 :symbol "tree_sitter_go"}
   :language/rust {:library "tree-sitter-rust"
                   :symbol "tree_sitter_rust"}
   :language/c {:library "tree-sitter-c"
                :symbol "tree_sitter_c"}
   :language/cpp {:library "tree-sitter-cpp"
                  :symbol "tree_sitter_cpp"}
   :language/ruby {:library "tree-sitter-ruby"
                   :symbol "tree_sitter_ruby"}
   :language/php {:library "tree-sitter-php"
                  :symbol "tree_sitter_php"}
   :language/bash {:library "tree-sitter-bash"
                   :symbol "tree_sitter_bash"}
   :language/clojure {:library "tree-sitter-clojure"
                      :symbol "tree_sitter_clojure"}})

(defonce ^:private loaded-core (atom nil))

(defn- platform []
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (str/lower-case (System/getProperty "os.arch"))
        cpu (if (contains? #{"aarch64" "arm64"} arch) "aarch64" "x86_64")]
    (cond
      (str/includes? os "linux") {:prefix (str cpu "-linux-gnu-") :suffix ".so"}
      (or (str/includes? os "mac") (str/includes? os "darwin"))
      {:prefix (str cpu "-macos-") :suffix ".dylib"}
      (str/includes? os "windows") {:prefix (str cpu "-windows-") :suffix ".dll"}
      :else (throw (ex-info (str "Unsupported native platform: " os " / " arch)
                            {:os os :arch arch})))))

(defn- resource-name [library]
  (let [{:keys [prefix suffix]} (platform)]
    (str "lib/" prefix library suffix)))

(defn- extract-library! [^Path directory library]
  (let [resource (resource-name library)
        url (io/resource resource)]
    (when-not url
      (throw (ex-info (str "Native parser library is not packaged: " library)
                      {:library library :resource resource})))
    (Files/createDirectories directory
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (let [target (.resolve directory (str (System/mapLibraryName library)))]
      (when-not (Files/exists target (make-array LinkOption 0))
        (with-open [input (io/input-stream url)]
          (Files/copy input target
                      (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
      target)))

(defn- load-core! [native-directory]
  (or @loaded-core
      (locking loaded-core
        (or @loaded-core
            (let [path (extract-library! native-directory "tree-sitter")]
              (System/setProperty "llm-context.tree-sitter.library" (str path))
              (.setContextClassLoader (Thread/currentThread)
                                      (native-lookup/provider-class-loader))
              (reset! loaded-core path))))))

(defn- load-language [^Arena arena native-directory language]
  (let [{:keys [library symbol]} (get language-definitions language)]
    (when-not library
      (throw (ex-info (str "No packaged Tree-sitter grammar for " language)
                      {:language language
                       :supported (set (keys language-definitions))})))
    (let [path (extract-library! native-directory library)
          lookup (SymbolLookup/libraryLookup path arena)]
      (Language/load lookup symbol))))

(defn- point-map [point prefix]
  {(keyword "source" (str prefix "-line")) (inc (.row point))
   (keyword "source" (str prefix "-column")) (inc (.column point))})

(declare node->map)

(defn- child->map [^Node parent index ^Node child]
  (cond-> (node->map child)
    (some? (.getFieldNameForNamedChild parent index))
    (assoc :field (.getFieldNameForNamedChild parent index))))

(defn- node->map [^Node node]
  (let [children (.getNamedChildren node)]
    (merge {:type (.getType node)
            :start-byte (.getStartByte node)
            :end-byte (.getEndByte node)
            :error? (.isError node)
            :missing? (.isMissing node)
            :children (mapv #(child->map node % (.get children %))
                            (range (.size children)))}
           (point-map (.getStartPoint node) "start")
           (point-map (.getEndPoint node) "end"))))

(defrecord JTreeSitterProvider [^Arena arena native-directory languages]
  provider/ParserProvider
  (supported-languages [_] (set (keys language-definitions)))
  (parse-source [_ language source]
    (let [grammar (or (get @languages language)
                      (get (swap! languages
                                  #(if (contains? % language)
                                     %
                                     (assoc % language
                                            (load-language arena native-directory language))))
                           language))]
      (with-open [parser (Parser. grammar)
                  tree (.orElseThrow (.parse parser source))]
        {:language language
         :source source
         :root (node->map (.getRootNode tree))})))

  Closeable
  (close [_] (.close arena)))

(defn open
  "Open the JVM Tree-sitter provider and extract packaged native libraries into
  project state. The core is process-global; grammar lookups live with the
  provider arena."
  [project]
  (let [directory (.resolve ^Path (:state-dir project) "native")]
    (load-core! directory)
    (->JTreeSitterProvider (Arena/ofShared) directory (atom {}))))
