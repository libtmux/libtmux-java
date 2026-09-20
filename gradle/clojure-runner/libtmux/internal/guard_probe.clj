(ns libtmux.internal.guard-probe
  (:import [java.net InetAddress ServerSocket]
           [java.util.concurrent ExecutorService Executors TimeUnit]))

(case (System/getProperty "libtmux.clojure.guard-probe")
  "executor" (with-open [^ExecutorService executor (Executors/newSingleThreadExecutor)] nil)
  "process" (let [^java.util.List argv [(str (System/getProperty "java.home") "/bin/java") "-version"]
                  process (.start (ProcessBuilder. argv))]
              (try
                (.readAllBytes (.getErrorStream process))
                (when-not (.waitFor process 900 TimeUnit/MILLISECONDS)
                  (throw (ex-info "guard probe child did not exit" {})))
                (finally (.destroyForcibly process))))
  "socket" (with-open [socket (ServerSocket. 0 0 (InetAddress/getLoopbackAddress))] nil)
  nil)
