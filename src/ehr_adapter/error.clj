(ns ehr-adapter.error)

(defmulti info (fn [code _data] code))

(defmethod info :default
  [code {:keys [scope operation message value expected]}]
  (ex-info message
           {:scope     scope
            :operation operation
            :code      code
            :details   {:value    value
                        :expected expected}}))

(defmethod info :invalid/type
  [code {:keys [scope operation message value expected]}]
  (ex-info message
           {:scope     scope
            :operation operation
            :code      code
            :details   {:value    value
                        :type     (type value)
                        :expected expected}}))

(defmethod info :invalid/schema
  [code {:keys [scope operation message details]}]
  (ex-info message
           {:scope     scope
            :operation operation
            :code      code
            :details   details}))

(defmethod info :invalid/format
  [code {:keys [scope operation message value expected]}]
  (ex-info message
           {:scope     scope
            :operation operation
            :code      code
            :details   {:format   value
                        :expected expected}}))
