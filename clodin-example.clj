;; we do not have exceptions yet, so the type errors that will
;; eventually trow exceptions should print an error and stop the
;; procedure execution until we have exceptions.

;; buffer-set get's element size from (size-of :T)
(defproc buffer-set [pointer [:T :*] index :i64 value :T]
  (store (pointer-add pointer
                      (* index (size-of :T)))
         value))

(comment
  ;; pointer syntax
  [:i64 :*] ;; pointer to i64
  [:i64 :* :*] ;; pointer to a pointer to i64

  [:i64 10 :*] ;; pointer to an array of lenght 10
  [:i64 10 :* :*] ;; pointer to a pointer to an array of lenght 10
  [Person :*] ;; pointer to a struct
  [[DynamicArray :T] :*] ;; pointer to a generic struct
  [[DynamicArray Person] :*] ;; pointer to a specialized generic struct

  [Person 10] ;; an array of 10 person structs

  ;; nil can be stored to pointer of any type
  (let [pointer [:i64 :*] nil]
    (set pointer (pointer-cast (malloc (* 5 (size-of :i64)))
                               [:i64 :*]))
    (buffer-set pointer 0 42))

  ;; initializing [:i32 :*] pointer with :k64* pointer throws an exception
  (let [pointer [:i32 :*] (pointer-cast (malloc (* 5 (size-of :i64)))
                                        [:i64 :*])])

  ;; initializing [:i32 :*] pointer with untyped :ptr pointer throws an
  ;; exception
  (let [pointer [:i32 :*] (malloc (* 5 (size-of :i64)))])

  ;; number literals are automatically cast to the expected type
  (let [pointer :i8 200])

  )

(defproc fill-buffer-i64 [pointer [:i64 :*] value :i64 count :i64]
  (let [index :i64 0]
    (while (< index count)
      (buffer-set pointer index value)
      (set index (+ index 1)))))

;; inferred type parameters are keywords that start with upper case
;; letter.  Built in types are keywords that start with a lower case
;; letter. User defined types are symbols that start with upper case
;; letter.
(defproc fill-buffer [pointer [:T :*] value :T count :i64]
  (let [index :i64 0]
    (while (< index count)
      (buffer-set pointer index value)
      (set index (+ index 1)))))

;; the array length is the template parameter :N. array-length gives
;; the length at compile time. array-set checks that index is less
;; than length. Accessing ;; array with two large index throws an
;; exception.
(defproc fill-array-i64 [array [:i64 :N] value :i64]
  (let [index :i64 0]
    (while (< index (array-length array))
      (array-set array index value)
      (set index (+ index 1)))
    array))


(comment
  ;; array types have fixed size at compile time. At runtime they are
  ;; bare pointers.
  (let [array [:i64 10] :zero-initialized] ;; zero initialized array
    (fill-array-i64 array 42))

  (let [array [:i64 10] :uninitialized] ;; uninitialized array
    (fill-array-i64 array 42))

  ;; array literals
  [:i64 [1 2 3]]
  [:i64 500 :zero-initialized]
  [:i64 500 :uninitialized]

  (let [array [:i64 3] [:i64 [1 2 3]]]
    (array-get array 1))

  ;; this mismatch in type length 6 and literal length 3 throws an
  ;; exception
  (let [array [:i64 6] [:i64 [1 2 3]]])

  )

(defstruc [DynamicArray :T]
  :pointer [:T :*]
  :capacity :i64
  :size :i64)

(defproc dynamic-array-set [dynamic-array [DynamicArray :T] index :i64 value :T]
  (when (< index (:size dynamic-array))
    (buffer-set (:pointer dynamic-array) index value)))

;; user defined types are symbols, because they have a definition in
;; the source code and they are represented as vars with the source
;; code file and line in the metadata
(defstruc String
  :size :i64
  :pointer [:i8 :*])

(comment
  ;; string literals compile to String structs
  (:size "Hello") evaluates to 5

  ;; this turns String struct into a null terminated c string by
  ;; copying the string and adding the terimnating null.
  ;; the c-string is stack allocated
  (clodin.core/to-c-string "Hello")

  )

(defstruc Person
  :name String
  :age :i8)

(comment

  ;; assoc sets one or more struct filelds and returns the struct
  ;; all of these return 10
  (:age (let [person Person :zero-initialized]
          (assoc person :age 10)
          (assoc person :name "Jack")))

  (:age (let [person Person :zero-initialized]
          (assoc person
                 :name "Jack"
                 :age 10)))

  (:age (assoc [Person :zero-initialized]
               :name "Jack"
               :age 10))

  ;; "pointer" creates a pointer to a variable. Assoc mutates the
  ;; pointee and returns the pointer
  (let [person Person :zero-initialized
        person-pointer [Person :*] (pointer person)]
    (assoc person-pointer :name "Jack")
    (assoc person-pointer :age 10))

  )

;; this creates person struct in the stack. Assoc calls mutate it and
;; finally it is copied to the caller as a retrun value
(defproc create-person [name String age :i8]
  (let [person Person :zero-initialized]
    (assoc person :name name)
    (assoc person :age age)))

;; this mutates the pointee and returns the pointer
(defproc set-person-name [person-pointer [Person :*]]
  (assoc person-pointer :name "Jack"))

(comment
  ;; struct literals
  [Person {:name "Jack"
           :age 50}]
  [Person :zero-initialized]
  [Person :uninitialized]

  ;; uninitialized fields in a struct literal are zero initialized
  ;; this returns 0
  (let [jack Person [Person {:name "Jack"}]]
    (:age jack))

  )
