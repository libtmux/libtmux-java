(ns libtmux.documentation-examples-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [libtmux.control :as control]
            [libtmux.core :as tmux]
            [libtmux.internal.fixture :as fixture])
  (:import [java.lang AutoCloseable]
           [java.nio.file Files LinkOption Path]
           [java.util UUID]))

(def ^:private fence
  #"(?ms)(?:<!--\s*clojure-snippet:\s*([^>\s]+)\s*-->\s*\n)?^```clojure\s*\n(.*?)^```")
(def ^:private output-line #"(?m)^\s*;;\s*=>\s*(.+?)\s*$")
(def ^:private required-documents ["libtmux-clojure/README.md"
                                  "docs/guide/clojure.md"
                                  "docs/guide/clojure-reference.md"])
(def ^:private socket-property "libtmux.docs.socket")
(def ^:private binary-property "libtmux.docs.binary")
(def ^:private fields-marker #"(?s)<!--\s*clojure-captured-fields:\s*(\[.*?])\s*-->")

(defn- document-root []
  (Path/of (System/getProperty "libtmux.docs.root") (make-array String 0)))

(defn- regular-file? [^Path path]
  (Files/isRegularFile path (make-array LinkOption 0)))

(defn- listed [^Path directory]
  (with-open [entries (Files/list directory)]
    (vec (.toList entries))))

(defn- require-documents! [^Path root]
  (doseq [relative required-documents
          :let [^String relative relative
                ^Path document (.resolve root relative)]]
    (when-not (regular-file? document)
      (throw (ex-info "required Clojure documentation is missing"
                      {:docs/error :missing-document :document relative}))))
  root)

(defn- readable-documents [^Path root]
  (require-documents! root)
  (let [readmes (->> (listed root)
                     (map (fn [^Path directory] (.resolve directory "README.md")))
                     (filter regular-file?))
        guides (->> (listed (.resolve root "docs/guide"))
                    (filter #(str/ends-with? (str (.getFileName ^Path %)) ".md")))]
    (->> (concat [(.resolve root "README.md")]
                 readmes
                 guides)
         (filter regular-file?)
         (sort-by str)
         vec)))

(defn- line-number [text offset]
  (inc (count (filter #(= \newline %) (subs text 0 offset)))))

(defn- snippet! [source line directive code]
  (when-not (= "fixture" directive)
    (throw (ex-info "Clojure documentation snippets require the fixture directive"
                    {:docs/error :missing-or-unknown-directive
                     :document source :line line :directive directive})))
  (when-not (re-find #"\(with-open\s+\[server\b" code)
    (throw (ex-info "Clojure documentation snippets must close their Server"
                    {:docs/error :missing-ownership :document source :line line})))
  (let [outputs (re-seq output-line code)]
    (when-not (= 1 (count outputs))
      (throw (ex-info "Clojure documentation snippets require one displayed EDN result"
                      {:docs/error :missing-or-ambiguous-output
                       :document source :line line :outputs (count outputs)})))
    (let [[_ expected] (first outputs)]
      {:document source
       :line line
       :code (str/replace code output-line "")
       :expected (edn/read-string expected)})))

(defn- snippets-in [^Path root ^Path document]
  (let [text (slurp (str document))
        matcher (re-matcher fence text)
        source (str (.relativize root document))]
    (loop [snippets []]
      (if (.find matcher)
        (recur (conj snippets
                     (snippet! source
                               (line-number text (.start matcher))
                               (.group matcher 1)
                               (.group matcher 2))))
        snippets))))

(defn- documented-snippets [^Path root]
  (->> (readable-documents root)
       (mapcat #(snippets-in root %))
       vec))

(defn- documented-fields! [^Path root]
  (let [reference (.resolve root "docs/guide/clojure-reference.md")
        markers (re-seq fields-marker (slurp (str reference)))]
    (when-not (= 1 (count markers))
      (throw (ex-info "Clojure field reference needs one field marker"
                      {:docs/error :missing-or-ambiguous-field-marker
                       :count (count markers)})))
    (let [fields (edn/read-string (second (first markers)))]
      (when-not (and (vector? fields)
                     (every? #(and (keyword? %) (namespace %)) fields))
        (throw (ex-info "Clojure field marker must contain qualified keywords"
                        {:docs/error :invalid-field-marker})))
      (set fields))))

(defn- captured-fields [value]
  (cond
    (map? value) (into (set (filter #(and (keyword? %) (namespace %)) (keys value)))
                       (mapcat captured-fields (vals value)))
    (sequential? value) (into #{} (mapcat captured-fields value))
    :else #{}))

(defn- projected-fields! []
  (fixture/with-owned-server
    (fn [{:keys [server]}]
      (let [session (first (tmux/sessions! server))]
        (with-open [^AutoCloseable connection (control/attach! (:tmux/ref session))]
          (captured-fields (tmux/snapshot! server)))))))

(defn- restore-property! [property value]
  (if value
    (System/setProperty property value)
    (System/clearProperty property)))

(defn- with-fixture-properties [^Path socket f]
  (let [socket-before (System/getProperty socket-property)
        binary-before (System/getProperty binary-property)]
    (try
      (System/setProperty socket-property (str socket))
      (System/setProperty binary-property (System/getProperty "libtmux.tmux"))
      (f)
      (finally
        (restore-property! socket-property socket-before)
        (restore-property! binary-property binary-before)))))

(defn- evaluate-snippet! [{:keys [document line code expected]} ^Path socket]
  (with-fixture-properties socket
    #(let [name (symbol (str "libtmux.docs.generated." (UUID/randomUUID)))
           namespace (create-ns name)]
       (try
         (binding [*ns* namespace]
           (refer 'clojure.core)
           (let [actual (eval (read-string (str "(do\n" code "\n)")))]
             (when-not (= expected actual)
               (throw (ex-info "Clojure documentation output differs from the evaluated result"
                               {:docs/error :output-mismatch
                                :document document :line line
                                :expected expected :actual actual})))
             actual))
         (finally
           (remove-ns name))))))

(defn- run-snippet! [snippet]
  (fixture/with-owned-server
    (fn [{:keys [socket]}]
      (evaluate-snippet! snippet socket))))

(defn- failure-data [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo failure
      (ex-data failure))))

(deftest required-documentation-is-present-and-discovered
  (let [root (document-root)
        snippets (documented-snippets root)]
    (is (seq snippets))
    (is (= #{"libtmux-clojure/README.md" "docs/guide/clojure.md"}
           (set (map :document snippets))))))

(deftest captured-field-reference-matches-the-projection
  (is (= (projected-fields!)
         (documented-fields! (document-root)))))

(deftest documented-snippets-open-owned-clients-and-match-their-output
  (doseq [snippet (documented-snippets (document-root))]
    (is (= (:expected snippet) (run-snippet! snippet))
        (str (:document snippet) ":" (:line snippet)))))

(deftest malformed-documentation-is-rejected-before-it-can-look-executable
  (is (= :missing-ownership
         (:docs/error (failure-data #(snippet! "guide.md" 1 "fixture" "(+ 1 1)\n;; => 2")))))
  (is (= :missing-or-ambiguous-output
         (:docs/error (failure-data #(snippet! "guide.md" 1 "fixture"
                                             "(with-open [server nil] 2)")))))
  (is (= :missing-or-unknown-directive
         (:docs/error (failure-data #(snippet! "guide.md" 1 nil
                                             "(with-open [server nil] 2)\n;; => 2")))))
  (let [directory (Files/createTempDirectory "libtmux-docs-"
                                               (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (is (= :missing-document
             (:docs/error (failure-data #(require-documents! directory)))))
      (finally
        (Files/deleteIfExists directory)))))

(deftest renamed-imports-and-wrong-displayed-output-fail-the-documentation-gate
  (let [root (document-root)
        snippet (first (documented-snippets root))
        renamed (assoc snippet :code "(require '[libtmux.missing :as tm])")]
    (is (thrown? Throwable (run-snippet! renamed)))
    (is (= :output-mismatch
           (:docs/error (failure-data #(run-snippet! (assoc snippet :expected ::wrong))))))))
