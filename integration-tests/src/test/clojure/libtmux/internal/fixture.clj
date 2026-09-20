(ns libtmux.internal.fixture
  (:import [io.github.libtmux Server ServerConfig ServerConfig$Builder ServerEndpoint]
           [io.github.libtmux.junit5 NamedServerFixture]
           [io.github.libtmux.transport CommandResult]
           [java.io IOException]
           [java.lang AutoCloseable ProcessHandle ProcessHandle$Info]
           [java.nio.file Files Path LinkOption]
           [java.nio.file.attribute BasicFileAttributes]
           [java.time Duration Instant]
           [java.util UUID]
           [java.util.concurrent TimeUnit]))

(def ^:private ^Path socket-root (Path/of "/tmp/libtmux-java-test" (make-array String 0)))
(def ^:dynamic *after-start* (fn [_ _]))

(defn- open-server [^Path socket ^Path config]
  (let [^ServerConfig$Builder builder (ServerConfig/builder)]
    (.binary builder (System/getProperty "libtmux.tmux" "tmux"))
    (.endpoint builder (ServerEndpoint/socketPath socket))
    (.configFile builder config)
    (.defaultTimeout builder (Duration/ofMillis 900))
    (Server/open (.build builder))))

(defn- command! [^Server server ^java.util.List argv]
  (.cmd server argv))

(defn- close-suppressing! [resource primary]
  (try
    (.close ^AutoCloseable resource)
    (catch Throwable cleanup
      (if-let [^Throwable failure @primary]
        (.addSuppressed failure cleanup)
        (throw cleanup)))))

(defn- remove-scratch! [^Path directory ^Path config]
  (Files/deleteIfExists config)
  (Files/deleteIfExists directory))

(defn- suppress! [primary cleanup]
  (if-let [^Throwable failure @primary]
    (.addSuppressed failure cleanup)
    (throw cleanup)))

(defn- process-identity [^ProcessHandle process]
  (let [^ProcessHandle$Info info (.info process)]
    {:process process
     :pid (.pid process)
     :command (.orElse (.command info) nil)
     :arguments (vec (.orElse (.arguments info) (make-array String 0)))
     :started (.orElse (.startInstant info) nil)}))

(defn- basename [^String path]
  (when path (str (.getFileName (Path/of path (make-array String 0))))))

(defn same-socket-path? [^Path expected ^String observed]
  (try
    (let [^"[Ljava.nio.file.LinkOption;" options (make-array LinkOption 0)
          ^Path actual (Path/of observed (make-array String 0))]
      (= (.toRealPath expected options) (.toRealPath actual options)))
    (catch IOException _ false)
    (catch java.nio.file.InvalidPathException _ false)))

(defn- tmux-for-socket? [identity binary ^Path socket]
  (let [command (:command identity)
        arguments (:arguments identity)
        executable (basename command)
        expected (basename binary)]
    (and (= expected executable)
         (some true?
               (map-indexed
                (fn [index argument]
                  (and (= "-S" argument)
                       (< (inc index) (count arguments))
                       (same-socket-path? socket (nth arguments (inc index)))))
                arguments)))))

(defn- socket-key [^Path socket]
  (let [^"[Ljava.nio.file.LinkOption;" options (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
        ^BasicFileAttributes attributes (Files/readAttributes socket BasicFileAttributes options)]
    (.fileKey attributes)))

(defn- capture-process [^Server server ^Path socket]
  (let [^CommandResult result (command! server ["display-message" "-p"
                                                 "#{pid}\t#{socket_path}"])
        rows (.stdout result)]
    (when-not (.succeeded result)
      (throw (ex-info "could not read fixture tmux identity" {:stderr (.stderr result)})))
    (when-not (= 1 (count rows))
      (throw (ex-info "tmux reported ambiguous fixture identities" {:rows rows})))
    (let [[pid reported-socket] (.split ^String (first rows) "\\t" -1)
          process (.orElseThrow (ProcessHandle/of (Long/parseLong pid)))]
      (when-not (same-socket-path? socket reported-socket)
        (throw (ex-info "tmux reported another fixture socket"
                        {:socket socket :reported-socket reported-socket})))
      (assoc (process-identity process) :socket-key (socket-key socket)))))

(defn- same-process? [owned]
  (let [^ProcessHandle process (:process owned)
        current (process-identity process)]
    (= (select-keys owned [:pid :command :arguments :started])
       (select-keys current [:pid :command :arguments :started]))))

(defn- stop-captured! [owned ^Path socket primary]
  (when owned
    (let [^ProcessHandle process (:process owned)]
      (if-not (same-process? owned)
        (suppress! primary
                   (ex-info "refusing to terminate a replacement tmux process"
                            {:socket socket :pid (:pid owned)}))
        (do
          (.destroy process)
          (try
            (.get (.onExit process) 900 TimeUnit/MILLISECONDS)
            (catch Exception cleanup
              (suppress! primary cleanup)))
          (when (.isAlive process)
            (suppress! primary
                       (ex-info "fixture tmux survived startup cleanup"
                                {:socket socket :pid (:pid owned)})))
          (when (and (not (.isAlive process))
                     (Files/exists socket (make-array LinkOption 0)))
            (if (and (:socket-key owned) (= (:socket-key owned) (socket-key socket)))
              (Files/delete socket)
              (suppress! primary (ex-info "refusing to reclaim a replacement fixture socket"
                                          {:socket socket})))))))))

(defn with-owned-server [body]
  (Files/createDirectories socket-root (make-array java.nio.file.attribute.FileAttribute 0))
  (let [directory (Files/createTempDirectory socket-root "clj-"
                                              (make-array java.nio.file.attribute.FileAttribute 0))
        socket (.resolve directory "s")
        config (.resolve directory "tmux.conf")]
    (Files/writeString config "" (make-array java.nio.file.OpenOption 0))
    (let [^Server server
          (try
            (open-server socket config)
            (catch Throwable failure
              (try
                (remove-scratch! directory config)
                (catch Throwable cleanup
                  (.addSuppressed failure cleanup)))
              (throw failure)))
          primary (atom nil)
          captured (atom nil)
          binary (System/getProperty "libtmux.tmux" "tmux")]
      (try
        (let [^CommandResult created
              (command! server ["new-session" "-d" "-s"
                                                 (str "clj-" (UUID/randomUUID))])]
          (when-not (.succeeded created)
            (throw (ex-info "could not start fixture tmux" {:stderr (.stderr created)}))))
        (reset! captured (capture-process server socket))
        (*after-start* server socket)
        (let [^NamedServerFixture owner (NamedServerFixture/own server socket socket-root)]
          (try
            (let [expected (System/getProperty "libtmux.tmux.expected")
                  ^CommandResult version-result
                  (command! server ["display-message" "-p" "#{version}"])
                  actual (first (.stdout version-result))]
              (when-not (.succeeded version-result)
                (throw (ex-info "could not read fixture tmux version"
                                {:stderr (.stderr version-result)})))
              (when (and expected (not= expected actual))
                (throw (ex-info "fixture tmux does not match the selected matrix lane"
                                {:expected expected :actual actual})))
              (body {:server server
                     :socket (.socket owner)
                     :binary binary
                     :expected-version expected
                     :actual-version actual}))
            (catch Throwable failure
              (reset! primary failure)
              (throw failure))
            (finally
              (close-suppressing! owner primary))))
        (catch Throwable failure
          (reset! primary failure)
          (when-not @captured
            (try
              (reset! captured (capture-process server socket))
              (catch Throwable cleanup
                (.addSuppressed failure cleanup))))
          (when (and (nil? @captured)
                     (Files/exists socket (make-array java.nio.file.LinkOption 0)))
            (.addSuppressed failure
                            (ex-info "refusing to terminate an unauthenticated tmux process"
                                     {:socket socket})))
          (stop-captured! @captured socket primary)
          (throw failure))
        (finally
          (close-suppressing! server primary)
          (when-not (Files/exists socket (make-array java.nio.file.LinkOption 0))
            (try
              (remove-scratch! directory config)
              (catch Throwable cleanup
                (suppress! primary cleanup)))))))))
