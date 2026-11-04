package mc2pc.common.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;

/** JSON único para chaincodes e apps. Use TreeMap em mapas para saída determinística. */
public final class Json {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    public static String to(Object o) {
        return GSON.toJson(o);
    }

    public static <T> T from(String json, Class<T> type) {
        return parse(json, type);
    }

    public static <T> T from(String json, Type type) {
        return parse(json, type);
    }

    private static <T> T parse(String json, Type type) {
        try {
            T v = GSON.fromJson(json, type);
            if (v == null) {
                throw new IllegalArgumentException("JSON vazio");
            }
            return v;
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("JSON inválido: " + e.getMessage(), e);
        }
    }
}
