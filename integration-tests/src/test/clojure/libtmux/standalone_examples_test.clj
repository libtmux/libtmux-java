(ns libtmux.standalone-examples-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [com.fasterxml.jackson.databind JsonNode ObjectMapper]
           [java.io File StringWriter]
           [java.lang ProcessHandle]
           [java.nio.file Files Path]
           [java.util.concurrent TimeUnit]))

(defn- ^Path root []
  (.toAbsolutePath (Path/of (System/getProperty "libtmux.docs.root" ".")
                            (make-array String 0))))

(defn- programs []
  (let [^JsonNode manifest (.readTree (ObjectMapper.)
                                     (.toFile (.resolve (root) "examples/api/manifest.json")))]
    (mapv (fn [^JsonNode node]
            {:id (.asText (.path node "id"))
             :sourceFile (.asText (.path node "sourceFile"))
             :mainNamespace (.asText (.path node "mainNamespace"))
             :expectedOutput (.asText (.path node "expectedOutput"))
             :targets (mapv #(.asText ^JsonNode %) (seq (.path node "targets")))})
          (filter #(= "clojure" (.asText (.path ^JsonNode % "variant")))
                  (seq (.path manifest "examples"))))))

(deftest manifest-covers-every-clojure-program
  (let [examples (programs)
        source-root (.resolve (root) "examples/src/main/clojure")
        sources (into #{}
                      (comp (filter #(.isFile ^File %))
                            (filter #(str/ends-with? (.getName ^File %) ".clj"))
                            (map #(str (.relativize (root) (.toPath ^File %)))))
                      (file-seq (.toFile source-root)))]
    (is (= 8 (count examples)))
    (is (= (set (map #(str "clojure-" %)
                     ["Connect" "ListSessions" "ListWindows" "ListPanes"
                      "NewSession" "NewWindow" "Query" "Capture"]))
           (set (map :id examples))))
    (is (= sources (set (map :sourceFile examples))))
    (doseq [{:keys [sourceFile mainNamespace targets]} examples]
      (is (str/ends-with? sourceFile
                         (str (-> mainNamespace (str/replace "." "/")
                                  (str/replace "-" "_")) ".clj")))
      (is (<= (count (str/split-lines (slurp (.toFile (.resolve (root) ^String sourceFile)))))
              150))
      (let [warnings (StringWriter.)]
        (binding [*ns* *ns* *err* warnings *warn-on-reflection* true]
          (load-file (str (.resolve (root) ^String sourceFile))))
        (is (str/blank? (str warnings)) (str warnings)))
      (doseq [target targets
              :let [qualified (symbol target)
                    namespace (symbol (namespace qualified))]]
        (require namespace)
        (is (contains? (ns-publics namespace) (symbol (name qualified))) target)))))

(defn- launch! [{:keys [mainNamespace]}]
  (let [base (io/file "/tmp/libtmux-java-test")
        _ (.mkdirs base)
        directory (Files/createTempDirectory (.toPath base) "clojure-api-"
                                              (make-array java.nio.file.attribute.FileAttribute 0))
        output (.resolve directory "output.log")
        classpath (str (.resolve (root) "examples/src/main/clojure")
                       File/pathSeparator (System/getProperty "java.class.path"))
        command ["sh" (str (.resolve (root) "examples/api/run.sh"))
                 (str (System/getProperty "java.home") "/bin/java")
                 "-XX:+UseSerialGC" "-XX:ActiveProcessorCount=2" "-XX:TieredStopAtLevel=1"
                 "-cp" classpath "clojure.main" "-m" mainNamespace]
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory (.toFile (root)))
                  (.redirectErrorStream true)
                  (.redirectOutput (.toFile output)))
        environment (.environment builder)]
    (.put environment "TMUX_BIN" (System/getProperty "libtmux.tmux" "tmux"))
    (.put environment "API_RUN_ROOT" (str directory))
    (let [process (.start builder)]
      (.close (.getOutputStream process))
      (try
        (.get (.onExit process) 20 TimeUnit/SECONDS)
        {:exit (.exitValue process) :output (slurp (.toFile output))}
        (finally
          (when (.isAlive process)
            (with-open [children (.descendants process)]
              (doseq [^ProcessHandle child (.toList children)] (.destroy child)))
            (.destroy process)
            (.get (.onExit process) 900 TimeUnit/MILLISECONDS))
          (when (zero? (.exitValue process))
            (Files/deleteIfExists output)
            (Files/delete directory)))))))

(deftest ^{:slow true :reason "Fresh CLI processes with owned real tmux servers; outer loop only."}
  programs-run-their-displayed-entry-points
  (doseq [{:keys [id expectedOutput] :as program} (programs)]
    (testing id
      (println "Standalone Clojure program:" id)
      (let [{:keys [exit output]} (launch! program)]
        (is (zero? exit) output)
        (is (= expectedOutput output))))))
