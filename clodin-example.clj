(defproc fill-buffer-i64 [pointer :i64* value :f64 count :i64]
  (let [index :i64 0]
    (while (< index count)
      (buffer-set pointer index value)
      (set index (+ index 1)))))

(defproc fill-buffer [pointer :T* value :T count :i64]
  (let [index :i64 0]
    (while (< index count)
      (buffer-set pointer index value)
      (set index (+ index 1)))))

(defproc fill-array-i64 [array [:i64] value :f64]
  (let [index :i64 0]
    (while (< index (array-length array))
      (array-set array index value)
      (set index (+ index 1)))
    array))

(defproc use-fill-array-i64 []
  (let [array [:i64 10]]
    (fill-array-i64 array 42)))

(defproc create-initialized-array []
  [:i64 1 2 3])

(defstruc [DynamicArray :T]
  :pointer :T*
  :capacity :i64
  :size :i64)

(defproc dynamic-array-set [dynamic-array [DynamicArray :T] index :i64 value :T]
  (when (< index (:size dynamic-array))
    (buffer-set (:pointer dynamic-array) index value)))

(defstruc String
  :length :i64
  :pointer :i8*)

(defstruc person
  :pointer String
  :age :i8)

(defproc create-person [name String age :i8]
  (let [person Person]
    (assoc person :name name)
    (assoc person :age age)
    person))

(defproc create-person-2 [name String age :i8]
  [Person {:name name
           :age age}])
