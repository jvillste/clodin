(ns clodin.globals
  "Top level mutable variables for clodin programs.

  Every global owns one storage slot in JIT memory. Native code reads and
  writes the slot with atomic acquire/release loads and stores, so a native
  loop that spins on a flag sees a change written by another thread.

  The Clojure side sees each global as a Global object that implements
  clojure.lang.IDeref and clojure.lang.IAtom2, so deref, reset!, swap!,
  compare-and-set!, reset-vals! and swap-vals! work on it exactly as they do
  on an atom. The object holds only the global's name: it resolves the
  current storage address on every access, so a JIT rebuild that moves or
  reinitialises the storage is picked up automatically.

  :boolean globals are stored as a 32 bit integer holding 0 or 1. LLVM can
  only perform atomic accesses on byte-sized types, and the JVM's VarHandle
  cannot compare-and-set a boolean, so an i32 slot is the one representation
  that gives real atomic loads, stores and compare-and-set for both sides.
  Native code still sees an i1 after the load and converts on the store."
  (:require [clodin.llvm-api :as llvm]
            [clojure.string :as string])
  (:import (clojure.lang IAtom2 IDeref IPersistentVector)
           (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.lang.invoke MethodHandle VarHandle$AccessMode)))

(def ^:private supported-types #{:i64 :i32 :f64 :f32 :boolean :ptr})

(defn supported-type?
  [type]
  (contains? supported-types type))

(defn storage-type
  "The clodin type used for the global's storage slot. Every type but
  :boolean is stored as itself; :boolean becomes :i32 so that atomic
  accesses are possible."
  [type]
  (if (= :boolean type) :i32 type))

(defn storage-size
  "The size in bytes of a global's storage slot."
  [type]
  (case (storage-type type)
    :i32 4
    :f32 4
    :i64 8
    :f64 8
    :ptr 8
    (throw (ex-info (str "No global storage size for type " type) {:type type}))))

(defn storage-layout
  "The Java memory layout of a global's storage slot."
  [type]
  (case (storage-type type)
    :i32 ValueLayout/JAVA_INT
    :f32 ValueLayout/JAVA_FLOAT
    :i64 ValueLayout/JAVA_LONG
    :f64 ValueLayout/JAVA_DOUBLE
    :ptr ValueLayout/ADDRESS
    (throw (ex-info (str "No global storage layout for type " type) {:type type}))))

;;; ---------------------------------------------------------------------------
;;; Registry
;;;
;;; registry maps a global name (a string) to
;;; {:namespace ... :name ... :type ... :initial-value ... :address ...
;;;  :jit ...}. :address is nil until the storage module is installed in the
;;;  current JIT; :jit identifies the JIT generation that installed it.
;;; ---------------------------------------------------------------------------

(def registry (atom {}))

(defn entry
  "The registry entry for a global, or nil."
  [name]
  (get @registry (str name)))

(defn global-type
  "The declared clodin type of a global."
  [name]
  (:type (entry name)))

(defn constant-value
  "An LLVM constant of the global's storage type for the given Clojure
  initial value. A :boolean global lives in an :i32 slot holding 0 or 1, so
  the value is normalised here rather than in the caller."
  [^MemorySegment context type value]
  (case (storage-type type)
    :i32 (llvm/const-int-of-type context :i32
                                  (if (= :boolean type)
                                    (if value 1 0)
                                    (long value)))
    :f32 (llvm/const-real context :f32 (double value))
    :i64 (llvm/const-int-of-type context :i64 (long value))
    :f64 (llvm/const-real context :f64 (double value))
    :ptr (llvm/const-null (llvm/type-for context :ptr))))

(defn- check-value!
  "Throws unless value can be stored in a global of the given type."
  [name type value]
  (case type
    (:i64 :i32) (when-not (integer? value)
                  (throw (ex-info (str "Global " name " of type " type
                                       " needs an integer initial value, got "
                                       (some-> value class .getName))
                                  {:name name :type type :value value})))
    (:f64 :f32) (when-not (number? value)
                  (throw (ex-info (str "Global " name " of type " type
                                       " needs a numeric initial value, got "
                                       (some-> value class .getName))
                                  {:name name :type type :value value})))
    :boolean (when-not (or (boolean? value)
                           (and (integer? value) (or (zero? value) (= 1 value))))
               (throw (ex-info (str "Global " name
                                    " of type :boolean needs true, false, 0 or 1, got "
                                    value)
                               {:name name :type type :value value})))
    :ptr (when-not (nil? value)
           (throw (ex-info (str "Global " name
                                " of type :ptr can only start as nil; reset! it"
                                " to a MemorySegment or an integer address later")
                           {:name name :type type :value value})))
    (throw (ex-info (str "Unsupported global type: " type)
                    {:name name :type type}))))

(defn- coerce-initial-value
  [type initial-value]
  (if (= :boolean type)
    (boolean (or (true? initial-value)
                 (and (integer? initial-value) (not (zero? initial-value)))))
    initial-value))

(defn register!
  "Registers or re-registers a global and returns [entry type-changed?].

  A redefinition with the same type is defonce-like: the entry keeps its
  address, so the live value and the JIT storage survive, and only the
  remembered source initial value changes. A redefinition with a different
  type mints a fresh native symbol (name$gN), because existing native code was
  compiled against the old storage layout. Callers then reinstall the storage
  and re-emit every body, the same way a procedure signature change is
  handled."
  [namespace name type initial-value]
  (let [name (str name)]
    (when (string/includes? name "$")
      (throw (ex-info "Global names must not contain '$'" {:name name})))
    (when-not (supported-type? type)
      (throw (ex-info (str "Unsupported global type: " type)
                      {:name name :type type})))
    (check-value! name type initial-value)
    (let [existing (entry name)
          type-changed? (and existing (not= type (:type existing)))
          revision (if type-changed? (inc (:revision existing 0)) 0)
          symbol (cond
                   (nil? existing) name
                   type-changed? (str name "$g" (max 1 revision))
                   :else (:symbol existing))]
      (swap! registry assoc name
             {:namespace namespace
              :name name
              :type type
              :initial-value (coerce-initial-value type initial-value)
              :symbol symbol
              :revision revision
              :address (when-not type-changed? (:address existing))
              :jit (when-not type-changed? (:jit existing))})
      [(entry name) type-changed?])))

(defn set-initial-value!
  "Records a new source initial value for an already registered global
  without changing its live storage, mirroring defonce."
  [name initial-value]
  (let [name (str name)
        existing (entry name)]
    (when-not existing
      (throw (ex-info (str "Unknown global: " name) {:name name})))
    (check-value! name (:type existing) initial-value)
    (swap! registry assoc-in [name :initial-value]
           (if (= :boolean (:type existing)) (boolean initial-value) initial-value))
    (entry name)))

(defn storage-specs
  "The [symbol type initial-value] triples for every registered global, used
  to build the storage modules after a reset."
  []
  (mapv (fn [[_name entry]]
          [(:symbol entry) (:type entry) (:initial-value entry)])
        @registry))

(defn install!
  "Records that the global's storage lives at address in jit. name is a
  registered global name and symbol is the native symbol that owns the
  storage."
  [name symbol address jit]
  (swap! registry update (str name)
         (fn [existing]
           (when-not existing
             (throw (ex-info (str "Unknown global: " name) {:name name})))
           (assoc existing :symbol symbol :address address :jit jit))))

(defn reset-installations!
  "Forgets every recorded storage address, so the next installation builds
  fresh storage modules with the source initial values. Used by a full JIT
  reset."
  []
  (swap! registry
         (fn [entries]
           (into {}
                 (map (fn [[name entry]]
                        [name (assoc entry :address nil :jit nil)]))
                 entries)))
  nil)

(defn installed-symbols
  "The native symbols whose storage is installed in the given JIT generation."
  [jit]
  (into #{}
        (keep (fn [[_name entry]]
                (when (and (:address entry) (= (:jit entry) jit))
                  (:symbol entry))))
        @registry))

;;; ---------------------------------------------------------------------------
;;; JVM access
;;;
;;; A Global object only knows its name. Every access looks the current
;;; address up in the registry, so a reset or a monolithic rebuild that moves
;;; the storage does not leave a stale reference behind.
;;; ---------------------------------------------------------------------------

(def ^:private access-handles
  "MethodHandles for the acquire load, release store and compare-and-set of
  each storage layout, keyed by storage type."
  (atom {}))

(defn- storage-handles
  [type]
  (let [storage-type (storage-type type)]
    (or (get @access-handles storage-type)
        (let [handle (.varHandle (storage-layout storage-type))]
          (get (swap! access-handles
                      (fn [handles]
                        (if (contains? handles storage-type)
                          handles
                          (assoc handles storage-type
                                 {:get (.toMethodHandle handle VarHandle$AccessMode/GET_ACQUIRE)
                                  :set (.toMethodHandle handle VarHandle$AccessMode/SET_RELEASE)
                                  :compare-and-set (.toMethodHandle handle
                                                                    VarHandle$AccessMode/COMPARE_AND_SET)}))))
               storage-type)))))

(defn- invoke
  [^MethodHandle handle arguments]
  (.invokeWithArguments handle (object-array arguments)))

(defn to-storage
  "Converts a Clojure value into the raw Java value the VarHandle stores."
  [type value]
  (case type
    :boolean (int (if value 1 0))
    :i64 (long (if (number? value)
                 (long value)
                 (throw (ex-info (str "Expected an integer for :i64, got " value)
                                 {:type type :value value}))))
    :i32 (int (if (number? value)
                (long value)
                (throw (ex-info (str "Expected an integer for :i32, got " value)
                                {:type type :value value}))))
    :f64 (double (if (number? value)
                   value
                   (throw (ex-info (str "Expected a number for :f64, got " value)
                                   {:type type :value value}))))
    :f32 (float (if (number? value)
                  value
                  (throw (ex-info (str "Expected a number for :f32, got " value)
                                  {:type type :value value}))))
    :ptr (cond
           (instance? MemorySegment value) value
           (nil? value) (MemorySegment/ofAddress 0)
           (integer? value) (MemorySegment/ofAddress (long value))
           :else (throw (ex-info (str "Expected a MemorySegment, nil or an integer address"
                                      " for :ptr, got " (some-> value class .getName))
                                 {:type type :value value})))
    (throw (ex-info (str "Unsupported global type: " type) {:type type}))))

(defn from-storage
  "Converts a raw Java value read from the storage slot into a Clojure value."
  [type value]
  (case type
    :boolean (not (zero? (int value)))
    :i64 (long value)
    :i32 (int value)
    :f64 (double value)
    :f32 (float value)
    :ptr (let [^MemorySegment segment value]
           (when-not (zero? (.address segment))
             segment))
    (throw (ex-info (str "Unsupported global type: " type) {:type type}))))

;; Set by clodin.compiler to a thunk that builds the JIT and installs global
;; storage if it is missing. The JVM side calls it lazily on the first access,
;; so @running? works even when no procedure has been called yet. It is an
;; atom rather than a var so this namespace does not have to depend on the
;; compiler.
(defonce ensure-storage! (atom nil))

(defn- ensure-installed!
  "Calls the compiler's installation hook, if any, when the global has no
  storage yet."
  [name]
  (when-not (:address (entry name))
    (when-let [hook @ensure-storage!]
      (hook)))
  (entry name))

(defn- storage-segment
  "A view of the global's current storage slot."
  ^MemorySegment [name type]
  (let [existing (ensure-installed! name)]
    (when-not existing
      (throw (ex-info (str "Unknown global: " name) {:name name})))
    (when-not (:address existing)
      (throw (ex-info (str "Global " name " has no storage yet; is the JIT built?")
                      {:name name})))
    (.reinterpret (MemorySegment/ofAddress (long (:address existing)))
                  (storage-size type))))

(defn read-value
  "Reads a global with an acquire load."
  [name]
  (let [{:keys [type]} (or (entry name)
                           (throw (ex-info (str "Unknown global: " name) {:name name})))
        {:keys [get]} (storage-handles type)]
    (from-storage type (invoke get [(storage-segment name type) (long 0)]))))

(defn write-value!
  "Writes a global with a release store and returns the value written."
  [name value]
  (let [{:keys [type]} (or (entry name)
                           (throw (ex-info (str "Unknown global: " name) {:name name})))
        {:keys [set]} (storage-handles type)
        stored (to-storage type value)]
    (invoke set [(storage-segment name type) (long 0) stored])
    value))

(defn compare-and-set-value!
  "Stores value if the global currently holds expected, comparing the raw
  storage representations."
  [name expected value]
  (let [{:keys [type]} (or (entry name)
                           (throw (ex-info (str "Unknown global: " name) {:name name})))
        {:keys [compare-and-set]} (storage-handles type)]
    (boolean
     (invoke compare-and-set [(storage-segment name type) (long 0)
                              (to-storage type expected)
                              (to-storage type value)]))))

(defn swap-value!
  "Atomically applies f to the global's value, retrying on contention, and
  returns the new value."
  [name f arguments]
  (loop []
    (let [old-value (read-value name)
          new-value (apply f old-value arguments)]
      (if (compare-and-set-value! name old-value new-value)
        new-value
        (recur)))))

(defn reset-value!
  "Sets the global's value and returns it."
  [name value]
  (write-value! name value))

(defn reset-values!
  "Sets the global's value and returns [old new]."
  [name value]
  (let [old-value (read-value name)]
    (write-value! name value)
    [old-value value]))

(defn swap-values!
  "Atomically applies f to the global's value and returns [old new]."
  [name f arguments]
  (loop []
    (let [old-value (read-value name)
          new-value (apply f old-value arguments)]
      (if (compare-and-set-value! name old-value new-value)
        [old-value new-value]
        (recur)))))

(deftype Global [name]
  IDeref
  (deref [_] (read-value name))

  IAtom2
  (reset [_ value] (reset-value! name value))
  (swap [_ f] (swap-value! name f []))
  (swap [_ f x] (swap-value! name f [x]))
  (swap [_ f x y] (swap-value! name f [x y]))
  (swap [_ f x y args] (swap-value! name f (into [x y] args)))
  (compareAndSet [_ expected value] (compare-and-set-value! name expected value))
  (resetVals [_ value] (reset-values! name value))
  (swapVals [_ f] (swap-values! name f []))
  (swapVals [_ f x] (swap-values! name f [x]))
  (swapVals [_ f x y] (swap-values! name f [x y]))
  (swapVals [_ f x y args] (swap-values! name f (into [x y] args)))

  Object
  (toString [_] (str "#<clodin global " name " " (pr-str (read-value name)) ">")))

(defn global-variable
  "The Clojure-side handle for a global. The object is stateless: it looks the
  global up by name on every access."
  [name]
  (Global. (str name)))
