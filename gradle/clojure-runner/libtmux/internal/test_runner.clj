(ns libtmux.internal.test-runner
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test])
  (:import [java.io File]
           [java.lang.reflect Method]
           [java.util.concurrent TimeUnit]))

(defn- configured-namespaces [property]
  (let [names (some-> (System/getProperty property) (str/split #","))]
    (when-not (seq names)
      (throw (ex-info (str "missing namespace list: " property) {:property property})))
    (mapv symbol names)))

(defn- source-namespaces [roots]
  (let [namespaces
        (mapcat
         (fn [root]
           (let [^File directory (io/file root)]
             (when-not (.isDirectory directory)
               (throw (ex-info "Clojure test source root does not exist" {:root root})))
             (let [names (->> (file-seq directory)
                              (filter (fn [^File source]
                                        (and (.isFile source)
                                             (str/ends-with? (.getName source) "_test.clj"))))
                              (map (fn [^File source]
                                     (-> (str (.relativize (.toPath directory) (.toPath source)))
                                         (str/replace File/separator ".")
                                         (str/replace "_" "-")
                                         (str/replace #"\.clj$" "")
                                         symbol)))
                              sort
                              vec)]
               (when (empty? names)
                 (throw (ex-info "Clojure test source root contains no test namespaces" {:root root})))
               names)))
         roots)]
    (when-not (= (count namespaces) (count (set namespaces)))
      (throw (ex-info "Clojure test source roots contain duplicate namespaces"
                      {:roots roots})))
    (vec namespaces)))

(defn- require-without-reflection [namespaces]
  (let [warnings (java.io.StringWriter.)]
    (binding [*warn-on-reflection* true
              *err* warnings]
      (doseq [namespace namespaces]
        (require namespace :reload)))
    (when-not (str/blank? (str warnings))
      (throw (ex-info "Clojure namespace load emitted compiler warnings"
                      {:warnings (str warnings)})))))

(defn- run-tests! [namespaces]
  (require-without-reflection namespaces)
  (let [{:keys [test fail error]} (apply test/run-tests namespaces)]
    (when (zero? test)
      (throw (ex-info "Clojure runner discovered no tests" {:namespaces namespaces})))
    (when (pos? (+ fail error))
      (throw (ex-info "Clojure tests failed" {:fail fail :error error})))))

(defn- guarded-load! [namespaces]
  (let [^Class guard (try (Class/forName "libtmux.internal.loadguard.LoadGuard")
                   (catch ClassNotFoundException cause
                     (throw (ex-info "Namespace loading requires the internal load-guard agent" {} cause))))
        ^Method begin (.getMethod guard "begin" (make-array Class 0))
        ^Method end (.getMethod guard "end" (make-array Class 0))
        ^Method coverage (.getMethod guard "coverage" (make-array Class 0))]
    (.invoke begin nil (object-array 0))
    (let [failure (try (require-without-reflection namespaces) nil
                       (catch Throwable thrown thrown))
          effects (vec (.invoke end nil (object-array 0)))]
      (when (seq effects)
        (binding [*out* *err*] (println "NAMESPACE_LOAD_EFFECTS" (pr-str effects)))
        (throw (ex-info "Namespace load created a forbidden resource" {:effects effects} failure)))
      (when failure (throw failure))
      (println "Namespace load guard:" (into (sorted-map) (.invoke coverage nil (object-array 0)))))))

(defn- guard-probes! []
  (let [agent (System/getProperty "libtmux.clojure.guard.agent")]
    (when-not agent
      (throw (ex-info "Namespace guard probes require the internal agent" {})))
    (let [property "libtmux.clojure.guard-probe" previous (System/getProperty property)]
      (try
        (doseq [probe ["executor" "process" "socket"]]
          (System/setProperty property probe)
          (let [failure (try (guarded-load! '[libtmux.internal.guard-probe]) nil
                             (catch clojure.lang.ExceptionInfo thrown thrown))]
            (when-not (seq (:effects (ex-data failure)))
              (throw (ex-info "Namespace guard did not reject the injected resource" {:probe probe} failure)))
            (println "Namespace guard rejected:" probe)))
        (finally
          (if previous (System/setProperty property previous) (System/clearProperty property)))))
    (doseq [probe ["fork-join" "virtual-executor" "thread" "virtual-thread"
                   "client-socket" "datagram" "nio-socket"
                   "unix-server-socket" "nio-datagram"]]
      (let [^java.util.List argv
            [(str (System/getProperty "java.home") "/bin/java")
             "-XX:TieredStopAtLevel=1" "-Xshare:off" agent
             "-cp" (System/getProperty "libtmux.clojure.guard.classpath")
             "libtmux.internal.loadguard.ResourceProbe" probe]
            process (.start (.redirectErrorStream (ProcessBuilder. argv) true))]
        (try
          (when-not (.waitFor process 900 TimeUnit/MILLISECONDS)
            (throw (ex-info "Namespace guard probe did not finish within its budget" {:probe probe})))
          (let [output (slurp (.getInputStream process))]
            (when-not (and (zero? (.exitValue process))
                           (str/includes? output "NAMESPACE_LOAD_EFFECTS"))
              (throw (ex-info "Namespace guard did not reject the injected resource"
                              {:probe probe :output output})))
            (println "Namespace guard rejected:" probe
                     (first (filter #(str/starts-with? % "NAMESPACE_LOAD_EFFECTS")
                                    (str/split-lines output)))))
          (finally
            (when (.isAlive process)
              (.destroyForcibly process)
              (.waitFor process 900 TimeUnit/MILLISECONDS))))))))

(defn -main [& [mode & roots]]
  (case mode
    "load" (guarded-load!
            (configured-namespaces "libtmux.clojure.main-namespaces"))
    "guard-probes" (guard-probes!)
    "test" (run-tests! (if (seq roots)
                        (source-namespaces roots)
                        (configured-namespaces "libtmux.clojure.test-namespaces")))
    (throw (ex-info "expected runner mode load, guard-probes, or test" {:mode mode}))))
