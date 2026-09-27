package app.margingo;

import java.util.Locale;

/** The few native messages, in the phone's language (Serbian, Norwegian, otherwise English). */
final class Txt {

    private Txt() {
    }

    static String t(String en, String sr, String nb) {
        String lang = Locale.getDefault().getLanguage();
        if ("sr".equals(lang) || "hr".equals(lang) || "bs".equals(lang)) return sr;
        if ("nb".equals(lang) || "no".equals(lang) || "nn".equals(lang)) return nb;
        return en;
    }
}
