(ns llm-context.runtime.setup
  "Interactive host setup guidance for the optional CUDA runtime."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [llm-context.accelerator :as accelerator]
            [llm-context.dependencies :as dependencies])
  (:import [java.nio.file Files LinkOption Path Paths]
           [java.util.concurrent TimeUnit]))

(defn- linux? []
  (str/starts-with? (str/lower-case (System/getProperty "os.name")) "linux"))

(def ^:private cuda-runtime-package
  (dependencies/value [:semantic :cuda :runtime-debian-package]))

(def ^:private cudnn-package
  (dependencies/value [:semantic :cuda :debian-package]))

(def ^:private cuda-libraries-package
  (dependencies/value [:semantic :cuda :libraries-debian-package]))

(defn- executable-on-path? [name]
  (boolean
   (some #(and (Files/isRegularFile ^Path % (make-array LinkOption 0))
               (Files/isExecutable ^Path %))
         (for [directory (str/split (or (System/getenv "PATH") "")
                                    (re-pattern java.io.File/pathSeparator))
               :when (seq directory)]
           (.resolve (Paths/get directory (make-array String 0)) name)))))

(defn- interactive? []
  (some? (System/console)))

(defn- confirm? [message]
  (print (str message " [y/N] "))
  (flush)
  (contains? #{"y" "yes"}
             (some-> (read-line) str/trim str/lower-case)))

(defn- package-manager-command [host]
  (when (and (linux?) (executable-on-path? "apt-get"))
    (let [prefix (if (= "root" (System/getProperty "user.name"))
                   []
                   (when (executable-on-path? "sudo") ["sudo"]))
          packages (cond-> []
                     (not (:cuda-runtime-present? host))
                     (conj cuda-runtime-package)
                     (not (:cuda-libraries-present? host))
                     (conj cuda-libraries-package)
                     (not (:cudnn-present? host))
                     (conj cudnn-package))]
      (when (seq packages)
        {:update (into prefix ["apt-get" "update"])
         :install (into prefix (concat ["apt-get" "-y" "install"] packages))
         :packages packages}))))

(defn- cuda-dependency-installation-eligible? [host]
  (accelerator/cuda-dependency-installation-eligible? host))

(defn- run-command! [command]
  (println "$" (str/join " " command))
  (let [builder (ProcessBuilder. ^java.util.List command)
        _ (.inheritIO builder)
        process (.start builder)
        completed? (.waitFor process 10 TimeUnit/MINUTES)]
    (and completed? (zero? (.exitValue process)))))

(defn- print-guidance! [host package-manager]
  (doseq [action (accelerator/host-actions host)]
    (println "Action:" action))
  (when (:wsl? host)
    (println "WSL guidance: install/update the NVIDIA CUDA-enabled driver on Windows;"
             "do not install a Linux NVIDIA driver inside WSL."))
  (when (and (linux?) (cuda-dependency-installation-eligible? host))
    (if package-manager
      (println "Ubuntu/Debian CUDA 12 command:"
               (str "sudo apt-get update && sudo apt-get -y install "
                    (str/join " " (:packages package-manager))))
      (println "Install the missing CUDA 12 runtime and/or cuDNN 9 using your distribution's package manager.")))
  (println "After correcting the host, install the matching runtime package with:"
           "LLM_CONTEXT_ACCELERATOR_PACKAGE=cuda sh install.sh"))

(defn run!
  "Report CUDA host readiness and optionally install missing CUDA 12 host libraries.

  System driver installation is deliberately not automated: WSL drivers belong
  on Windows, while native Linux drivers are hardware/distribution-specific.
  `--install-cudnn --yes` is the explicit non-interactive exception for the
  supported Ubuntu/Debian apt packages. The legacy flag installs whichever of
  CUDA 12's runtime and cuDNN 9 is missing."
  [{:keys [install-cudnn? yes?]}]
  (let [host (accelerator/cuda-host-readiness {} nil)
        package-manager (package-manager-command host)]
    (println "llm-context accelerator setup")
    (println (accelerator/describe-host host))
    (if (:ready? host)
      (println "Static CUDA host prerequisites are present. The first CUDA runtime startup remains the final probe.")
      (do
        (print-guidance! host package-manager)
        (when (and (cuda-dependency-installation-eligible? host) package-manager)
          (let [install? (cond
                            yes? true
                            (interactive?)
                            (confirm? "Install the missing CUDA 12 runtime/cuDNN 9 packages now?")
                            :else false)]
            (when install?
              (if (and (run-command! (:update package-manager))
                       (run-command! (:install package-manager)))
                (println "CUDA host dependency installation completed. Rerun setup and install the CUDA-enabled semantic runtime.")
                (println "CUDA host dependency installation failed; keep the CPU runtime and inspect the command output.")))
            (when (and (or install-cudnn? yes?) (not install?))
              (println "CUDA host dependencies were not installed: use --yes for non-interactive setup or run setup in a terminal.")))
        (when (and (or install-cudnn? yes?)
                   (cuda-dependency-installation-eligible? host)
                   (not package-manager))
          (println "CUDA host dependencies were not installed automatically: no supported apt-get package manager was found.")))))
    0))
