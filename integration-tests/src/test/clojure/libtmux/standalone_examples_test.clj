(ns libtmux.standalone-examples-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [libtmux.internal.control-gate :as gate]
            [libtmux.internal.fixture :as fixture])
  (:import [com.fasterxml.jackson.databind JsonNode ObjectMapper]
           [java.io File StringWriter]
           [java.lang ProcessHandle]
           [java.nio.file Files Path]
           [java.util.concurrent TimeUnit]))

(defn- ^Path root []
  (.toAbsolutePath (Path/of (System/getProperty "libtmux.docs.root" ".")
                            (make-array String 0))))

(defn- ^JsonNode catalog []
  (.readTree (ObjectMapper.)
             (.toFile (.resolve (root) "examples/api/clojure/manifest.json"))))

(defn- programs [^JsonNode manifest]
    (mapv (fn [^JsonNode node]
            {:id (.asText (.path node "id"))
             :language (.asText (.path node "language"))
             :sourceFile (.asText (.path node "sourceFile"))
             :path (.asText (.path node "path"))
             :mainNamespace (.asText (.path node "mainNamespace"))
             :expectedOutput (.asText (.path node "expectedOutput"))
             :requiresControl (.asBoolean (.path node "requiresControl"))
             :unsupportedOutput (.asText (.path node "unsupportedOutput"))
             :targets (mapv #(.asText ^JsonNode %) (seq (.path node "targets")))})
          (filter #(= "clojure" (.asText (.path ^JsonNode % "variant")))
                  (seq (.path manifest "examples")))))

(deftest manifest-covers-every-clojure-program
  (let [manifest (catalog)
        examples (programs manifest)
        setup (seq (.. manifest (path "variants") (path "clojure") (path "files")))
        source-root (.resolve (root) "examples/src/main/clojure")
        sources (into #{}
                      (comp (filter #(.isFile ^File %))
                            (filter #(str/ends-with? (.getName ^File %) ".clj"))
                            (map #(str (.relativize (root) (.toPath ^File %)))))
                      (file-seq (.toFile source-root)))]
    (is (= 1 (.asInt (.path manifest "schemaVersion"))))
    (is (= 1 (count setup)))
    (is (= #{"deps.edn"} (set (map #(.asText (.path ^JsonNode % "path")) setup))))
    (doseq [^JsonNode file setup]
      (is (.isFile (.toFile (.resolve (root) (.asText (.path file "sourceFile")))))))
    (is (= 13 (count examples)))
    (is (= (set (map #(str "clojure-" %)
                     ["Connect" "ListSessions" "ListWindows" "ListPanes"
                      "NewSession" "NewWindow" "Query" "Capture" "Workspace"
                      "CaptureConcurrently" "WatchPaneOutput" "RunACommand"
                      "ObserveWithAsync"]))
           (set (map :id examples))))
    (is (= sources (set (map :sourceFile examples))))
    (doseq [{:keys [sourceFile path language mainNamespace targets requiresControl unsupportedOutput]} examples]
      (is (= "clojure" language))
      (is (= sourceFile (str "examples/" path)))
      (when requiresControl (is (not (str/blank? unsupportedOutput))))
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
  (let [started (System/nanoTime)
        base (io/file "/tmp/libtmux-java-test")
        _ (.mkdirs base)
        directory (Files/createTempDirectory (.toPath base) "clojure-api-"
                                              (make-array java.nio.file.attribute.FileAttribute 0))
        output (.resolve directory "output.log")
        classpath (str (.resolve (root) "examples/src/main/clojure")
                       File/pathSeparator (System/getProperty "java.class.path"))
        command [(str (System/getProperty "java.home") "/bin/java")
                 "-Xmx384m" "-XX:+UseSerialGC" "-XX:ActiveProcessorCount=2" "-XX:TieredStopAtLevel=1"
                 "-Dclojure.main.report=stderr"
                 "-cp" classpath "clojure.main" "-m" mainNamespace]
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory (.toFile (root)))
                  (.redirectErrorStream true)
                  (.redirectOutput (.toFile output)))
        environment (.environment builder)]
    ;; The program starts its own server with the tmux first on PATH; the matrix names its build.
    (let [tmux (Path/of (System/getProperty "libtmux.tmux" "tmux") (make-array String 0))]
      (when (.isAbsolute tmux)
        (.put environment "PATH" (str (.getParent tmux) File/pathSeparator (.get environment "PATH")))))
    (let [process (.start builder)]
      (.close (.getOutputStream process))
      (try
        (.get (.onExit process) 20 TimeUnit/SECONDS)
        {:exit (.exitValue process) :output (slurp (.toFile output))
         :elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)}
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
  (let [supported (delay (fixture/with-owned-server
                         (fn [{:keys [server]}] (gate/supported? server))))]
    (doseq [{:keys [id expectedOutput requiresControl unsupportedOutput] :as program} (programs (catalog))]
      (testing id
        (println "Standalone Clojure program:" id)
        (let [{:keys [exit output elapsed-ms]} (launch! program)
              expected (if (and requiresControl (not @supported))
                         unsupportedOutput expectedOutput)]
          (println "CLI milliseconds:" id (long elapsed-ms))
          (is (zero? exit) output)
          (is (= expected output)))))))
