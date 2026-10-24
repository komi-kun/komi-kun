// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// JNI bridge between the vendored `hoshidicts` engine and
// `mihon.core.dictionary.engine.HoshiDictsNative` (contract §2.1).
//
// Ported from Chimahon's `hoshidicts_jni.cpp` (GPL-3.0) with the package strings moved to
// `mihon/core/dictionary/{engine,model}` and the model records extended with
// `ImportResult.errors`, `TermResult.score` and `PitchEntry.transcriptions`.
// The FindClass/GetMethodID literals MUST stay in sync with the Kotlin model
// constructors (contract §3.5) — the R8 rules in consumer-rules.pro keep them, and
// `JniSignatureTest` asserts the signatures on the JVM side.
//
// Robustness (contract §2.1): null jstring/jobjectArray arguments are tolerated, a failed
// `FindClass` short-circuits to an empty/failed result instead of dereferencing a null
// `jclass`, and every native call is wrapped so a C++ exception becomes a Kotlin-visible
// result (a failed `ImportResult` or an empty collection) rather than an abort.
#include <jni.h>
#include <pthread.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "hoshidicts/include/hoshidicts.h"
#include "hoshidicts/src/text_processor/text_processor.hpp"

namespace {
    struct Slot {
        uint64_t hash;
        uint64_t offset;
    };

    struct LookupObject {
        DictionaryQuery query;
        Deinflector deinflector;
        std::unique_ptr<Lookup> lookup;

        LookupObject() : lookup(std::make_unique<Lookup>(query, deinflector)) {}
    };

    LookupObject *as_object(jlong handle) { return reinterpret_cast<LookupObject *>(handle); }

    // Deinflection is dictionary-independent (hoshidicts README: "this doesn't use any dictionary
    // data"), so the transform entry points below share one immutable rule table instead of
    // building a `Deinflector` per call (its constructor parses ~2k rules). Function-local statics
    // are thread-safe and `Deinflector::deinflect` is `const`, so concurrent readers are safe.
    Deinflector &shared_deinflector() {
        static Deinflector instance;
        return instance;
    }

    // The Kotlin facade passes valid, non-null handles, but a zero handle would otherwise
    // dereference nullptr after a failed createSession/close race.
    bool valid_session(LookupObject *obj) { return obj != nullptr; }

    std::string jstring_to_std_string(JNIEnv *env, jstring input) {
        if (input == nullptr) {
            return {};
        }
        const char *chars = env->GetStringUTFChars(input, nullptr);
        if (chars == nullptr) {
            return {};
        }
        std::string output(chars);
        env->ReleaseStringUTFChars(input, chars);
        return output;
    }

    template<typename Fn>
    void for_each_string(JNIEnv *env, jobjectArray arr, Fn fn) {
        if (arr == nullptr) {
            return;
        }
        const jsize count = env->GetArrayLength(arr);
        for (jsize i = 0; i < count; ++i) {
            auto element = reinterpret_cast<jstring>(env->GetObjectArrayElement(arr, i));
            if (element == nullptr) {
                continue;
            }
            fn(jstring_to_std_string(env, element));
            env->DeleteLocalRef(element);
        }
    }

    jstring new_string(JNIEnv *env, const std::string &value) {
        return env->NewStringUTF(value.c_str());
    }

    // Kotlin `List<T>` is a `java.util.ArrayList` at the JNI boundary.
    jobject new_array_list(JNIEnv *env, jsize capacity) {
        jclass cls = env->FindClass("java/util/ArrayList");
        if (cls == nullptr) {
            return nullptr;
        }
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(I)V");
        if (ctor == nullptr) {
            return nullptr;
        }
        return env->NewObject(cls, ctor, capacity);
    }

    void list_add(JNIEnv *env, jobject list, jobject item) {
        if (list == nullptr) {
            return;
        }
        jclass cls = env->FindClass("java/util/ArrayList");
        if (cls == nullptr) {
            return;
        }
        jmethodID add = env->GetMethodID(cls, "add", "(Ljava/lang/Object;)Z");
        if (add == nullptr) {
            return;
        }
        env->CallBooleanMethod(list, add, item);
    }

    jobject new_string_list(JNIEnv *env, const std::vector<std::string> &values) {
        jobject list = new_array_list(env, static_cast<jsize>(values.size()));
        for (const auto &value : values) {
            jstring item = new_string(env, value);
            if (item == nullptr) {
                break;
            }
            list_add(env, list, item);
            env->DeleteLocalRef(item);
        }
        return list;
    }

    jobject new_import_result(JNIEnv *env, const ImportResult &result) {
        jclass cls = env->FindClass("mihon/core/dictionary/model/ImportResult");
        if (cls == nullptr) {
            return nullptr;
        }
        jmethodID ctor = env->GetMethodID(
                cls, "<init>",
                "(ZLjava/lang/String;JJJJJJLjava/util/List;)V");
        if (ctor == nullptr) {
            return nullptr;
        }
        jstring jtitle = new_string(env, result.title);
        jobject errors = new_string_list(env, result.errors);
        jobject out = env->NewObject(
                cls, ctor, static_cast<jboolean>(result.success), jtitle,
                static_cast<jlong>(result.term_count),
                static_cast<jlong>(result.meta_count),
                static_cast<jlong>(result.freq_count),
                static_cast<jlong>(result.pitch_count),
                static_cast<jlong>(result.kanji_count),
                static_cast<jlong>(result.media_count),
                errors);
        env->DeleteLocalRef(jtitle);
        env->DeleteLocalRef(errors);
        return out;
    }

    jobject new_transform_group(JNIEnv *env, const TransformGroup &tg) {
        jclass cls = env->FindClass("mihon/core/dictionary/model/TransformGroup");
        if (cls == nullptr) {
            return nullptr;
        }
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
        if (ctor == nullptr) {
            return nullptr;
        }
        jstring name = new_string(env, tg.name);
        jstring desc = new_string(env, tg.description);
        jobject out = env->NewObject(cls, ctor, name, desc);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(desc);
        return out;
    }

    jobject new_process_list(JNIEnv *env, const std::vector<TransformGroup> &trace) {
        jobject list = new_array_list(env, static_cast<jsize>(trace.size()));
        for (const auto &tg : trace) {
            jobject item = new_transform_group(env, tg);
            if (item == nullptr) {
                break;
            }
            list_add(env, list, item);
            env->DeleteLocalRef(item);
        }
        return list;
    }

    // `DeinflectionResult(text, conditions, trace)`; `trace` is in *application* order (see
    // `HoshiDictsNative.deinflect`).
    jobject new_deinflection_result(JNIEnv *env, const DeinflectionResult &result) {
        jclass cls = env->FindClass("mihon/core/dictionary/model/DeinflectionResult");
        if (cls == nullptr) {
            return nullptr;
        }
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;ILjava/util/List;)V");
        if (ctor == nullptr) {
            return nullptr;
        }
        jstring text = new_string(env, result.text);
        jobject trace = new_process_list(env, result.trace);
        jobject out = env->NewObject(cls, ctor, text, static_cast<jint>(result.conditions), trace);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(trace);
        return out;
    }

    jobject new_glossary_entry(JNIEnv *env, const GlossaryEntry &entry) {
        jclass cls = env->FindClass("mihon/core/dictionary/model/GlossaryEntry");
        if (cls == nullptr) {
            return nullptr;
        }
        jmethodID ctor = env->GetMethodID(
                cls, "<init>",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (ctor == nullptr) {
            return nullptr;
        }
        jstring dict_name = new_string(env, entry.dict_name);
        jstring glossary = new_string(env, entry.glossary);
        jstring definition_tags = new_string(env, entry.definition_tags);
        jstring term_tags = new_string(env, entry.term_tags);
        jobject out = env->NewObject(cls, ctor, dict_name, glossary, definition_tags, term_tags);
        env->DeleteLocalRef(dict_name);
        env->DeleteLocalRef(glossary);
        env->DeleteLocalRef(definition_tags);
        env->DeleteLocalRef(term_tags);
        return out;
    }

    jobject new_glossary_entry_list(JNIEnv *env, const std::vector<GlossaryEntry> &entries) {
        jobject list = new_array_list(env, static_cast<jsize>(entries.size()));
        for (const auto &entry : entries) {
            jobject item = new_glossary_entry(env, entry);
            if (item == nullptr) {
                break;
            }
            list_add(env, list, item);
            env->DeleteLocalRef(item);
        }
        return list;
    }

    jobject new_frequency(JNIEnv *env, const Frequency &frequency) {
        jclass cls = env->FindClass("mihon/core/dictionary/model/Frequency");
        if (cls == nullptr) {
            return nullptr;
        }
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
        if (ctor == nullptr) {
            return nullptr;
        }
        jstring display_value = new_string(env, frequency.display_value);
        jobject out = env->NewObject(cls, ctor, static_cast<jint>(frequency.value), display_value);
        env->DeleteLocalRef(display_value);
        return out;
    }

    jobject new_frequency_list(JNIEnv *env, const std::vector<Frequency> &frequencies) {
        jobject list = new_array_list(env, static_cast<jsize>(frequencies.size()));
        for (const auto &frequency : frequencies) {
            jobject item = new_frequency(env, frequency);
            if (item == nullptr) {
                break;
            }
            list_add(env, list, item);
            env->DeleteLocalRef(item);
        }
        return list;
    }

    jobject new_frequency_entry(JNIEnv *env, const FrequencyEntry &entry) {
        jclass cls = env->FindClass("mihon/core/dictionary/model/FrequencyEntry");
        if (cls == nullptr) {
            return nullptr;
        }
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/util/List;)V");
        if (ctor == nullptr) {
            return nullptr;
        }
        jstring dict_name = new_string(env, entry.dict_name);
        jobject frequencies = new_frequency_list(env, entry.frequencies);
        jobject out = env->NewObject(cls, ctor, dict_name, frequencies);
        env->DeleteLocalRef(dict_name);
        env->DeleteLocalRef(frequencies);
        return out;
    }

    jobject new_frequency_entry_list(JNIEnv *env, const std::vector<FrequencyEntry> &entries) {
        jobject list = new_array_list(env, static_cast<jsize>(entries.size()));
        for (const auto &entry : entries) {
            jobject item = new_frequency_entry(env, entry);
            if (item == nullptr) {
                break;
            }
            list_add(env, list, item);
            env->DeleteLocalRef(item);
        }
        return list;
    }

    jobject new_pitch_entry(JNIEnv *env, const PitchEntry &entry) {
        jclass cls = env->FindClass("mihon/core/dictionary/model/PitchEntry");
        if (cls == nullptr) {
            return nullptr;
        }
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;[ILjava/util/List;)V");
        if (ctor == nullptr) {
            return nullptr;
        }
        jstring dict_name = new_string(env, entry.dict_name);
        jintArray positions = env->NewIntArray(static_cast<jsize>(entry.pitch_positions.size()));
        if (!entry.pitch_positions.empty()) {
            env->SetIntArrayRegion(positions, 0, static_cast<jsize>(entry.pitch_positions.size()),
                                   reinterpret_cast<const jint *>(entry.pitch_positions.data()));
        }
        jobject transcriptions = new_string_list(env, entry.transcriptions);
        jobject out = env->NewObject(cls, ctor, dict_name, positions, transcriptions);
        env->DeleteLocalRef(dict_name);
        env->DeleteLocalRef(positions);
        env->DeleteLocalRef(transcriptions);
        return out;
    }

    jobject new_pitch_entry_list(JNIEnv *env, const std::vector<PitchEntry> &entries) {
        jobject list = new_array_list(env, static_cast<jsize>(entries.size()));
        for (const auto &entry : entries) {
            jobject item = new_pitch_entry(env, entry);
            if (item == nullptr) {
                break;
            }
            list_add(env, list, item);
            env->DeleteLocalRef(item);
        }
        return list;
    }

    jobject new_term_result(JNIEnv *env, const TermResult &term) {
        jclass cls = env->FindClass("mihon/core/dictionary/model/TermResult");
        if (cls == nullptr) {
            return nullptr;
        }
        jmethodID ctor = env->GetMethodID(
                cls, "<init>",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/util/List;Ljava/util/List;Ljava/util/List;)V");
        if (ctor == nullptr) {
            return nullptr;
        }
        jstring expression = new_string(env, term.expression);
        jstring reading = new_string(env, term.reading);
        jstring rules = new_string(env, term.rules);
        jobject glossaries = new_glossary_entry_list(env, term.glossaries);
        jobject frequencies = new_frequency_entry_list(env, term.frequencies);
        jobject pitches = new_pitch_entry_list(env, term.pitches);
        jobject out = env->NewObject(cls, ctor, expression, reading, rules,
                                     static_cast<jint>(term.score), glossaries, frequencies, pitches);
        env->DeleteLocalRef(expression);
        env->DeleteLocalRef(reading);
        env->DeleteLocalRef(rules);
        env->DeleteLocalRef(glossaries);
        env->DeleteLocalRef(frequencies);
        env->DeleteLocalRef(pitches);
        return out;
    }

    jobject new_term_result_list(JNIEnv *env, const std::vector<TermResult> &results) {
        jobject list = new_array_list(env, static_cast<jsize>(results.size()));
        for (const auto &term : results) {
            jobject item = new_term_result(env, term);
            if (item == nullptr) {
                break;
            }
            list_add(env, list, item);
            env->DeleteLocalRef(item);
        }
        return list;
    }

    jobject new_lookup_result(JNIEnv *env, const LookupResult &result) {
        jclass cls = env->FindClass("mihon/core/dictionary/model/LookupResult");
        if (cls == nullptr) {
            return nullptr;
        }
        jmethodID ctor = env->GetMethodID(
                cls, "<init>",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Lmihon/core/dictionary/model/TermResult;I)V");
        if (ctor == nullptr) {
            return nullptr;
        }
        jstring matched = new_string(env, result.matched);
        jstring deinflected = new_string(env, result.deinflected);
        jobject process = new_process_list(env, result.trace);
        jobject term = new_term_result(env, result.term);
        jobject out = env->NewObject(cls, ctor, matched, deinflected, process, term,
                                     static_cast<jint>(result.preprocessor_steps));
        env->DeleteLocalRef(matched);
        env->DeleteLocalRef(deinflected);
        env->DeleteLocalRef(process);
        env->DeleteLocalRef(term);
        return out;
    }

    jobjectArray new_lookup_result_array(JNIEnv *env, const std::vector<LookupResult> &results) {
        jclass cls = env->FindClass("mihon/core/dictionary/model/LookupResult");
        if (cls == nullptr) {
            return nullptr;
        }
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(results.size()), cls, nullptr);
        if (array == nullptr) {
            return nullptr;
        }
        for (size_t i = 0; i < results.size(); ++i) {
            jobject item = new_lookup_result(env, results[i]);
            if (item == nullptr) {
                break;
            }
            env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
            env->DeleteLocalRef(item);
        }
        return array;
    }

    jobject new_dictionary_style(JNIEnv *env, const DictionaryStyle &style) {
        jclass cls = env->FindClass("mihon/core/dictionary/model/DictionaryStyle");
        if (cls == nullptr) {
            return nullptr;
        }
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
        if (ctor == nullptr) {
            return nullptr;
        }
        jstring dict_name = new_string(env, style.dict_name);
        jstring styles = new_string(env, style.styles);
        jobject out = env->NewObject(cls, ctor, dict_name, styles);
        env->DeleteLocalRef(dict_name);
        env->DeleteLocalRef(styles);
        return out;
    }

    jobjectArray new_dictionary_style_array(JNIEnv *env, const std::vector<DictionaryStyle> &styles) {
        jclass cls = env->FindClass("mihon/core/dictionary/model/DictionaryStyle");
        if (cls == nullptr) {
            return nullptr;
        }
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(styles.size()), cls, nullptr);
        if (array == nullptr) {
            return nullptr;
        }
        for (size_t i = 0; i < styles.size(); ++i) {
            jobject entry = new_dictionary_style(env, styles[i]);
            if (entry == nullptr) {
                break;
            }
            env->SetObjectArrayElement(array, static_cast<jsize>(i), entry);
            env->DeleteLocalRef(entry);
        }
        return array;
    }

    // A defensive empty array so a thrown native call degrades to "no results" instead of
    // returning null (the Kotlin facade declares these return types non-null).
    jobjectArray empty_array(JNIEnv *env, const char *className) {
        jclass cls = env->FindClass(className);
        if (cls == nullptr) {
            return nullptr;
        }
        return env->NewObjectArray(0, cls, nullptr);
    }

    jobject empty_kanji_result(JNIEnv *env, const std::string &character) {
        jclass resultCls = env->FindClass("mihon/core/dictionary/model/KanjiResult");
        if (resultCls == nullptr) {
            return nullptr;
        }
        jmethodID resultCtor = env->GetMethodID(resultCls, "<init>",
                                                "(Ljava/lang/String;Ljava/util/List;)V");
        if (resultCtor == nullptr) {
            return nullptr;
        }
        jstring character_jstr = new_string(env, character);
        jobject entries = new_array_list(env, 0);
        jobject out = env->NewObject(resultCls, resultCtor, character_jstr, entries);
        env->DeleteLocalRef(character_jstr);
        env->DeleteLocalRef(entries);
        return out;
    }

    // Import thread arguments
    struct ImportArgs {
        std::string zip_path;
        std::string output_dir;
        ImportResult result;
    };

    // Thread function for import with a large stack.
    void *import_thread_func(void *arg) {
        ImportArgs *args = static_cast<ImportArgs *>(arg);
        try {
            args->result = dictionary_importer::import(args->zip_path, args->output_dir, true);
        } catch (const std::exception &e) {
            args->result.success = false;
            args->result.errors.emplace_back(e.what());
        } catch (...) {
            args->result.success = false;
            args->result.errors.emplace_back("unknown error during dictionary import");
        }
        return nullptr;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_mihon_core_dictionary_engine_HoshiDictsNative_createSession(JNIEnv *, jobject) {
    try {
        return reinterpret_cast<jlong>(new LookupObject());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_mihon_core_dictionary_engine_HoshiDictsNative_destroySession(JNIEnv *, jobject, jlong session) {
    if (session == 0) {
        return;
    }
    try {
        delete as_object(session);
    } catch (...) {
        // A destructor must not escape into the JVM.
    }
}

extern "C" JNIEXPORT void JNICALL
Java_mihon_core_dictionary_engine_HoshiDictsNative_rebuildQuery(JNIEnv *env, jobject, jlong session,
                                                                jobjectArray term_paths,
                                                                jobjectArray freq_paths,
                                                                jobjectArray pitch_paths,
                                                                jobjectArray kanji_paths) {
    LookupObject *obj = as_object(session);
    if (!valid_session(obj)) {
        return;
    }
    try {
        obj->query = DictionaryQuery{};
        for_each_string(env, term_paths,
                        [&](const std::string &path) { obj->query.add_term_dict(path); });
        for_each_string(env, freq_paths,
                        [&](const std::string &path) { obj->query.add_freq_dict(path); });
        for_each_string(env, pitch_paths,
                        [&](const std::string &path) { obj->query.add_pitch_dict(path); });
        for_each_string(env, kanji_paths,
                        [&](const std::string &path) { obj->query.add_kanji_dict(path); });
        obj->lookup = std::make_unique<Lookup>(obj->query, obj->deinflector);
    } catch (...) {
        obj->lookup = std::make_unique<Lookup>(obj->query, obj->deinflector);
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_mihon_core_dictionary_engine_HoshiDictsNative_queryKanji(JNIEnv *env, jobject, jlong session,
                                                              jstring character) {
    LookupObject *obj = as_object(session);
    const auto text_str = jstring_to_std_string(env, character);
    if (!valid_session(obj)) {
        return empty_kanji_result(env, text_str);
    }

    KanjiResult result;
    try {
        result = obj->query.query_kanji(text_str);
    } catch (...) {
        return empty_kanji_result(env, text_str);
    }

    jclass entryCls = env->FindClass("mihon/core/dictionary/model/KanjiEntry");
    if (entryCls == nullptr) {
        return empty_kanji_result(env, result.character);
    }
    jmethodID entryCtor = env->GetMethodID(
            entryCls, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/util/Map;)V");
    jclass mapCls = env->FindClass("java/util/HashMap");
    if (entryCtor == nullptr || mapCls == nullptr) {
        return empty_kanji_result(env, result.character);
    }
    jmethodID mapCtor = env->GetMethodID(mapCls, "<init>", "()V");
    jmethodID mapPut = env->GetMethodID(mapCls, "put",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    if (mapCtor == nullptr || mapPut == nullptr) {
        return empty_kanji_result(env, result.character);
    }

    jobject entryList = new_array_list(env, static_cast<jsize>(result.entries.size()));

    for (size_t i = 0; i < result.entries.size(); ++i) {
        const auto &e = result.entries[i];

        jobject dictName = new_string(env, e.dict_name);
        jobject onyomi = new_string(env, e.onyomi);
        jobject kunyomi = new_string(env, e.kunyomi);
        jobject tags = new_string(env, e.tags);

        jobject definitions = new_string_list(env, e.definitions);

        jobject stats = env->NewObject(mapCls, mapCtor);
        if (stats != nullptr) {
            for (const auto &[k, v] : e.stats) {
                jobject key = new_string(env, k);
                jobject val = new_string(env, v);
                env->CallObjectMethod(stats, mapPut, key, val);
                env->DeleteLocalRef(key);
                env->DeleteLocalRef(val);
            }
        }

        jobject entry = env->NewObject(entryCls, entryCtor, dictName, onyomi, kunyomi, tags,
                                       definitions, stats);
        if (entry == nullptr) {
            env->DeleteLocalRef(dictName);
            env->DeleteLocalRef(onyomi);
            env->DeleteLocalRef(kunyomi);
            env->DeleteLocalRef(tags);
            env->DeleteLocalRef(definitions);
            env->DeleteLocalRef(stats);
            break;
        }
        list_add(env, entryList, entry);

        env->DeleteLocalRef(dictName);
        env->DeleteLocalRef(onyomi);
        env->DeleteLocalRef(kunyomi);
        env->DeleteLocalRef(tags);
        env->DeleteLocalRef(definitions);
        env->DeleteLocalRef(stats);
        env->DeleteLocalRef(entry);
    }

    jclass resultCls = env->FindClass("mihon/core/dictionary/model/KanjiResult");
    if (resultCls == nullptr) {
        return empty_kanji_result(env, result.character);
    }
    jmethodID resultCtor = env->GetMethodID(resultCls, "<init>",
                                            "(Ljava/lang/String;Ljava/util/List;)V");
    if (resultCtor == nullptr) {
        return empty_kanji_result(env, result.character);
    }
    jobject character_jstr = new_string(env, result.character);
    jobject out = env->NewObject(resultCls, resultCtor, character_jstr, entryList);
    env->DeleteLocalRef(character_jstr);
    env->DeleteLocalRef(entryList);
    return out;
}

extern "C" JNIEXPORT jobject JNICALL
Java_mihon_core_dictionary_engine_HoshiDictsNative_importDictionary(JNIEnv *env, jobject,
                                                                    jstring zip_path,
                                                                    jstring output_dir) {
    ImportArgs args;
    args.zip_path = jstring_to_std_string(env, zip_path);
    args.output_dir = jstring_to_std_string(env, output_dir);

    if (zip_path == nullptr || output_dir == nullptr ||
        args.zip_path.empty() || args.output_dir.empty()) {
        args.result.success = false;
        args.result.errors.emplace_back("importDictionary requires a non-null zip and output dir");
    } else {
        // Create a thread with a 16 MB stack to handle the radix sort's large stack allocations.
        pthread_t thread;
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setstacksize(&attr, 16 * 1024 * 1024);  // 16 MB stack

        int rc = pthread_create(&thread, &attr, import_thread_func, &args);
        pthread_attr_destroy(&attr);

        if (rc == 0) {
            pthread_join(thread, nullptr);
        } else {
            // Fallback: run on the current thread (risky, but better than silent failure).
            import_thread_func(&args);
        }
    }

    if (!args.result.success && !args.result.errors.empty()) {
        args.result.title = args.result.errors[0];
    }
    return new_import_result(env, args.result);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_mihon_core_dictionary_engine_HoshiDictsNative_lookup(JNIEnv *env, jobject, jlong session,
                                                          jstring text, jint max_results,
                                                          jint scan_length) {
    LookupObject *obj = as_object(session);
    if (!valid_session(obj) || obj->lookup == nullptr) {
        return empty_array(env, "mihon/core/dictionary/model/LookupResult");
    }
    const auto text_str = jstring_to_std_string(env, text);
    try {
        auto result = obj->lookup->lookup(text_str, static_cast<int>(max_results),
                                          static_cast<size_t>(scan_length));
        return new_lookup_result_array(env, result);
    } catch (...) {
        return empty_array(env, "mihon/core/dictionary/model/LookupResult");
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_mihon_core_dictionary_engine_HoshiDictsNative_query(JNIEnv *env, jobject, jlong session,
                                                         jstring text) {
    LookupObject *obj = as_object(session);
    if (!valid_session(obj)) {
        return empty_array(env, "mihon/core/dictionary/model/TermResult");
    }
    const auto text_str = jstring_to_std_string(env, text);
    try {
        auto results = obj->query.query(text_str);
        jclass cls = env->FindClass("mihon/core/dictionary/model/TermResult");
        if (cls == nullptr) {
            return nullptr;
        }
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(results.size()), cls, nullptr);
        if (array == nullptr) {
            return nullptr;
        }
        for (size_t i = 0; i < results.size(); ++i) {
            jobject item = new_term_result(env, results[i]);
            if (item == nullptr) {
                break;
            }
            env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
            env->DeleteLocalRef(item);
        }
        return array;
    } catch (...) {
        return empty_array(env, "mihon/core/dictionary/model/TermResult");
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_mihon_core_dictionary_engine_HoshiDictsNative_getStyles(JNIEnv *env, jobject, jlong session) {
    LookupObject *obj = as_object(session);
    if (!valid_session(obj)) {
        return empty_array(env, "mihon/core/dictionary/model/DictionaryStyle");
    }
    try {
        auto styles = obj->query.get_styles();
        return new_dictionary_style_array(env, styles);
    } catch (...) {
        return empty_array(env, "mihon/core/dictionary/model/DictionaryStyle");
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_mihon_core_dictionary_engine_HoshiDictsNative_getMediaFile(JNIEnv *env, jobject, jlong session,
                                                                jstring dict_name,
                                                                jstring media_path) {
    LookupObject *obj = as_object(session);
    if (!valid_session(obj)) {
        return nullptr;
    }
    const auto dict_name_str = jstring_to_std_string(env, dict_name);
    const auto media_path_str = jstring_to_std_string(env, media_path);
    try {
        auto data = obj->query.get_media_file(dict_name_str, media_path_str);
        if (data.empty()) {
            return nullptr;
        }
        jbyteArray result = env->NewByteArray(static_cast<jsize>(data.size()));
        if (result == nullptr) {
            return nullptr;
        }
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size()),
                                reinterpret_cast<const jbyte *>(data.data()));
        return result;
    } catch (...) {
        return nullptr;
    }
}

// Host (T2) builds bake a digest of the engine sources they were compiled from, so the
// conformance runner can prove the library it loaded is the one that build produced (see
// `engine-build-info` in the test). Android/NDK builds do not define it and report an empty string.
extern "C" JNIEXPORT jstring JNICALL
Java_mihon_core_dictionary_engine_HoshiDictsNative_engineBuildFingerprint(JNIEnv *env, jobject) {
#ifdef HOSHIDICTS_ENGINE_SOURCE_FINGERPRINT
    return new_string(env, HOSHIDICTS_ENGINE_SOURCE_FINGERPRINT);
#else
    return env->NewStringUTF("");
#endif
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_mihon_core_dictionary_engine_HoshiDictsNative_deinflect(JNIEnv *env, jobject, jstring text) {
    jclass cls = env->FindClass("mihon/core/dictionary/model/DeinflectionResult");
    if (cls == nullptr) {
        return nullptr;
    }
    const auto text_str = jstring_to_std_string(env, text);
    std::vector<DeinflectionResult> results;
    try {
        results = shared_deinflector().deinflect(text_str);
    } catch (...) {
        return env->NewObjectArray(0, cls, nullptr);
    }
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(results.size()), cls, nullptr);
    if (array == nullptr) {
        return nullptr;
    }
    for (size_t i = 0; i < results.size(); ++i) {
        jobject item = new_deinflection_result(env, results[i]);
        if (item == nullptr) {
            break;
        }
        env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    return array;
}

extern "C" JNIEXPORT jint JNICALL
Java_mihon_core_dictionary_engine_HoshiDictsNative_posToConditions(JNIEnv *env, jobject,
                                                                   jobjectArray part_of_speech) {
    std::vector<std::string> values;
    for_each_string(env, part_of_speech, [&](const std::string &value) { values.push_back(value); });
    try {
        return static_cast<jint>(Deinflector::pos_to_conditions(values));
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_mihon_core_dictionary_engine_HoshiDictsNative_textVariants(JNIEnv *env, jobject, jstring text) {
    const auto text_str = jstring_to_std_string(env, text);
    try {
        auto variants = text_processor::process(text_str);
        jclass cls = env->FindClass("java/lang/String");
        if (cls == nullptr) {
            return nullptr;
        }
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(variants.size()), cls, nullptr);
        if (array == nullptr) {
            return nullptr;
        }
        for (size_t i = 0; i < variants.size(); ++i) {
            jstring item = new_string(env, variants[i].text);
            if (item == nullptr) {
                break;
            }
            env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
            env->DeleteLocalRef(item);
        }
        return array;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_mihon_core_dictionary_engine_HoshiDictsNative_probeEntryTypes(JNIEnv *env, jobject,
                                                                   jstring dict_path) {
    const auto dir = jstring_to_std_string(env, dict_path);

    auto make_zeros = [&]() {
        jlongArray result = env->NewLongArray(4);
        if (result == nullptr) {
            return result;
        }
        jlong zeros[4] = {0, 0, 0, 0};
        env->SetLongArrayRegion(result, 0, 4, zeros);
        return result;
    };

    if (dict_path == nullptr || dir.empty()) {
        return make_zeros();
    }

    std::string hashPath = dir + "/hash.table";
    std::string blobsPath = dir + "/blobs.bin";

    FILE *hashFile = fopen(hashPath.c_str(), "rb");
    if (!hashFile) return make_zeros();

    uint32_t capacity;
    if (fread(&capacity, sizeof(capacity), 1, hashFile) != 1) {
        fclose(hashFile);
        return make_zeros();
    }

    std::vector<Slot> slots(capacity);
    if (fread(slots.data(), sizeof(Slot), capacity, hashFile) != capacity) {
        fclose(hashFile);
        return make_zeros();
    }
    fclose(hashFile);

    FILE *blobsFile = fopen(blobsPath.c_str(), "rb");
    if (!blobsFile) return make_zeros();

    fseek(blobsFile, 0, SEEK_END);
    size_t blobsSize = static_cast<size_t>(ftell(blobsFile));
    fseek(blobsFile, 0, SEEK_SET);
    std::vector<uint8_t> blobs(blobsSize);
    if (fread(blobs.data(), 1, blobsSize, blobsFile) != blobsSize) {
        fclose(blobsFile);
        return make_zeros();
    }
    fclose(blobsFile);

    uint64_t termCount = 0, freqCount = 0, pitchCount = 0, kanjiCount = 0;

    for (uint32_t i = 0; i < capacity; i++) {
        if (slots[i].hash == 0) continue;

        uint64_t blobOffset = slots[i].offset;
        if (blobOffset + sizeof(uint32_t) > blobsSize) continue;

        uint32_t entryCount;
        std::memcpy(&entryCount, blobs.data() + blobOffset, sizeof(entryCount));
        size_t idx = blobOffset + sizeof(entryCount);

        for (uint32_t j = 0; j < entryCount; j++) {
            if (idx + sizeof(uint64_t) > blobsSize) break;
            uint64_t entryOffset;
            std::memcpy(&entryOffset, blobs.data() + idx, sizeof(entryOffset));
            idx += sizeof(entryOffset);

            if (entryOffset + 1 > blobsSize) continue;
            uint8_t type = blobs[entryOffset];

            if (type == 0) {
                termCount++;
            } else if (type == 1) {
                size_t pos = entryOffset + 1;
                if (pos + 2 > blobsSize) continue;
                uint16_t exprLen;
                std::memcpy(&exprLen, blobs.data() + pos, sizeof(exprLen));
                pos += 2 + exprLen;
                if (pos + 1 > blobsSize) continue;
                uint8_t modeLen = blobs[pos];
                pos += 1;
                if (pos + static_cast<size_t>(modeLen) > blobsSize) continue;
                std::string mode(reinterpret_cast<const char *>(blobs.data() + pos), modeLen);

                if (mode == "freq") {
                    freqCount++;
                } else if (mode == "pitch") {
                    pitchCount++;
                }
            } else if (type == 2) {
                kanjiCount++;
            }
        }
    }

    jlongArray result = env->NewLongArray(4);
    if (result == nullptr) {
        return nullptr;
    }
    jlong counts[4] = {
            static_cast<jlong>(termCount),
            static_cast<jlong>(freqCount),
            static_cast<jlong>(pitchCount),
            static_cast<jlong>(kanjiCount),
    };
    env->SetLongArrayRegion(result, 0, 4, counts);
    return result;
}
// KUN <--
