(ns llm-context.runtime.setup-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.accelerator :as accelerator]
            [llm-context.runtime.setup :as setup]))

(def host
  {:device-visible? true
   :driver-present? true
   :driver-compatible? true
   :libcuda-present? true
   :cuda-runtime-present? true
   :cudnn-present? false
   :ready? false
   :wsl? false
   :gpu-name "Test GPU"
   :driver-version "591.86"
   :minimum-driver accelerator/minimum-cuda-driver})

(def package-manager
  {:update ["apt-get" "update"]
   :install ["apt-get" "-y" "install" "cudnn9-cuda-12"]})

(deftest setup-does-not-offer-cudnn-for-a-hidden-gpu
  (let [prompted? (atom false)]
    (with-redefs-fn {#'accelerator/cuda-host-readiness
                     (constantly (assoc host :device-visible? false))
                     #'setup/package-manager-command (constantly package-manager)
                     #'setup/interactive? (constantly true)
                     #'setup/confirm? (fn [_]
                                        (reset! prompted? true)
                                        true)}
      #(setup/run! {:install-cudnn? false :yes? false}))
    (is (false? @prompted?))))

(deftest setup-confirms-before-installing-cudnn
  (let [calls (atom [])]
    (with-redefs-fn {#'accelerator/cuda-host-readiness (constantly host)
                     #'setup/package-manager-command (constantly package-manager)
                     #'setup/interactive? (constantly true)
                     #'setup/confirm? (fn [_]
                                        (swap! calls conj :confirm)
                                        true)
                     #'setup/run-command! (fn [command]
                                            (swap! calls conj command)
                                            true)}
      #(setup/run! {:install-cudnn? true :yes? false}))
    (is (= [:confirm
            ["apt-get" "update"]
            ["apt-get" "-y" "install" "cudnn9-cuda-12"]]
           @calls))))
