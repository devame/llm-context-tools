(ns llm-context.runtime.setup
  "Interactive host setup guidance for the optional CUDA runtime."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [llm-context.accelerator :as accelerator])
  (:import [java.nio.file Files LinkOption Path Paths]
           [java.util.concurrent TimeUnit]))

(defn- linux? []
  (str/starts-with? (str/lower-case (System/getProperty "os.name")) "linux"))

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

(defn- package-manager-command []
  (when (and (linux?) (executable-on-path? "apt-get"))
    (let [prefix (if (= "root" (System/getProperty "user.name"))
                   []
                   (when (executable-on-path? "sudo") ["sudo"]))]
      {:update (into prefix ["apt-get" "update"])
       :install (into prefix ["apt-get" "-y" "install" "cudnn9-cuda-12"])})))

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
  (when (and (linux?) (not (:cudnn-present? host)))
    (if package-manager
      (println "Ubuntu/Debian CUDA 12 command:"
               "sudo apt-get update && sudo apt-get -y install cudnn9-cuda-12")
      (println "Install cuDNN 9 for CUDA 12 using your distribution's package manager.")))
  (println "After correcting the host, install the matching runtime package with:"
           "LLM_CONTEXT_ACCELERATOR_PACKAGE=cuda sh install.sh"))

(defn run!
  "Report CUDA host readiness and optionally install cuDNN 9 for CUDA 12.

  System driver installation is deliberately not automated: WSL drivers belong
  on Windows, while native Linux drivers are hardware/distribution-specific.
  `--install-cudnn --yes` is the explicit non-interactive exception for the
  supported Ubuntu/Debian apt package."
  [{:keys [install-cudnn? yes?]}]
  (let [host (accelerator/cuda-host-readiness {} nil)
        package-manager (package-manager-command)]
    (println "llm-context accelerator setup")
    (println (accelerator/describe-host host))
    (if (:ready? host)
      (println "Static CUDA host prerequisites are present. The first CUDA runtime startup remains the final probe.")
      (do
        (print-guidance! host package-manager)
        (when (and (not (:cudnn-present? host)) package-manager)
          (let [install? (or (and install-cudnn?
                                  (or yes? (interactive?)))
                             (and (not install-cudnn?)
                                  (interactive?)
                                  (confirm? "Install cuDNN 9 for CUDA 12 now?")))]
            (when install?
              (if (and (run-command! (:update package-manager))
                       (run-command! (:install package-manager)))
                (println "cuDNN installation completed. Rerun setup and then install the CUDA runtime package.")
                (println "cuDNN installation failed; keep the CPU runtime and inspect the command output.")))
            (when (and install-cudnn? (not install?))
              (println "cuDNN was not installed: use --yes for non-interactive setup or run setup in a terminal.")))
        (when (and install-cudnn? (not package-manager))
          (println "cuDNN was not installed automatically: no supported apt-get package manager was found.")))))
    0))
