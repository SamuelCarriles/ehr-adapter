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

(defmethod info :invalid/reference
  [code {:keys [scope operation message reference context ref-bindings]}]
  (ex-info message
           {:scope scope
            :operation operation
            :code code
            :details {:reference reference
                      :context context
                      :ref-bindings ref-bindings}}))

(defmethod info :unsupported/mime-code
  [code {:keys [scope operation message mime-code expected]}]
  (ex-info message
           {:scope scope
            :operation operation
            :code code
            :details {:mime-code mime-code
                      :expected expected}}))

(defmethod info :missing/field
  [code {:keys [scope operation message field]}]
  (ex-info message
           {:scope scope
            :operation operation
            :code code
            :details {:field field}}))
