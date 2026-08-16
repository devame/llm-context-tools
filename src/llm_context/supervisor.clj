(ns llm-context.supervisor
  "Generate host-native supervision definitions without installing them."
  (:require [clojure.string :as str])
  (:import [java.nio.file Files Path Paths]))

(def supported-formats #{:systemd :launchd :windows})

(defn- resolve-executable ^Path [value]
  (let [candidate (Paths/get value (make-array String 0))]
    (if (.isAbsolute candidate)
      (.normalize candidate)
      (or
       (some (fn [directory]
               (let [path (.normalize
                           (.resolve (Paths/get directory (make-array String 0))
                                     value))]
                 (when (and (Files/isRegularFile path (make-array java.nio.file.LinkOption 0))
                            (Files/isExecutable path))
                   path)))
             (str/split (or (System/getenv "PATH") "")
                        (re-pattern java.io.File/pathSeparator)))
       (throw (ex-info (str "Executable is not available on PATH: " value)
                       {:exit-code 2 :type :supervisor/executable-missing
                        :executable value}))))))

(defn- systemd-quote [value]
  (str "\"" (-> (str value)
                  (str/replace "\\" "\\\\")
                  (str/replace "\"" "\\\"")) "\""))

(defn- xml-escape [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn- ps-quote [value]
  (str "'" (str/replace (str value) "'" "''") "'"))

(defn- project-label [project]
  (format "llm-context-%08x"
          (bit-and 0xffffffff (hash (str (:root project))))))

(defn systemd-unit [project executable]
  (let [root (:root project)]
    (str "[Unit]\n"
         "Description=llm-context resident service for " root "\n"
         "After=local-fs.target\n\n"
         "StartLimitIntervalSec=300\n"
         "StartLimitBurst=10\n\n"
         "[Service]\n"
         "Type=simple\n"
         "WorkingDirectory=" (systemd-quote root) "\n"
         "ExecStart=" (systemd-quote executable) " -C "
         (systemd-quote root) " service foreground\n"
         "Restart=on-failure\n"
         "RestartSec=5s\n"
         "TasksMax=64\n"
         "LimitNOFILE=8192\n"
         "UMask=0077\n"
         "StandardOutput=journal\n"
         "StandardError=journal\n\n"
         "[Install]\n"
         "WantedBy=default.target\n")))

(defn launchd-plist [project executable]
  (let [label (project-label project)
        root (xml-escape (:root project))
        executable (xml-escape executable)]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
         "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
         "<plist version=\"1.0\"><dict>\n"
         "  <key>Label</key><string>tools." label "</string>\n"
         "  <key>ProgramArguments</key><array>\n"
         "    <string>" executable "</string><string>-C</string>\n"
         "    <string>" root "</string><string>service</string>\n"
         "    <string>foreground</string>\n"
         "  </array>\n"
         "  <key>WorkingDirectory</key><string>" root "</string>\n"
         "  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>\n"
         "  <key>ThrottleInterval</key><integer>5</integer>\n"
         "  <key>ProcessType</key><string>Background</string>\n"
         "  <key>SoftResourceLimits</key><dict>\n"
         "    <key>NumberOfFiles</key><integer>8192</integer>\n"
         "  </dict>\n"
         "</dict></plist>\n")))

(defn windows-script [project executable]
  (let [label (project-label project)
        root (ps-quote (:root project))
        executable (ps-quote executable)]
    (str "$action = New-ScheduledTaskAction -Execute " executable
         " -Argument ('-C \"' + " root " + '\" service foreground') "
         "-WorkingDirectory " root "\n"
         "$settings = New-ScheduledTaskSettingsSet -RestartCount 10 "
         "-RestartInterval (New-TimeSpan -Seconds 5) "
         "-MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) "
         "-Priority 6\n"
         "Register-ScheduledTask -TaskName '" label "' "
         "-Action $action -Settings $settings -Description "
         "'llm-context resident project service'\n")))

(defn render
  [project {:keys [format executable]}]
  (when-not (contains? supported-formats format)
    (throw (ex-info "Supervisor format must be systemd, launchd, or windows"
                    {:exit-code 2 :type :supervisor/invalid-format
                     :format format})))
  (let [executable (resolve-executable (or executable "llm-context"))]
    (case format
      :systemd (systemd-unit project executable)
      :launchd (launchd-plist project executable)
      :windows (windows-script project executable))))
