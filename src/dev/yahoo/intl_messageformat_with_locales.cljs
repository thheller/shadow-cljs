(ns yahoo.intl-messageformat-with-locales
  (:require ["intl-messageformat" :as intl-messageformat-with-locales]
            [goog.object :as gobj]))

(gobj/set js/window "IntlMessageFormat" intl-messageformat-with-locales)