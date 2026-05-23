package agentica.settings

import org.scalatest.funsuite.AnyFunSuite
import upickle.default.*

class SettingsStoreTest extends AnyFunSuite
{
    // ── APIMode serialisation ─────────────────────────────────────────────────

    test("APIMode.ChatCompletions serialises to \"chatcompletions\"") {
        assert(write[APIMode](APIMode.ChatCompletions) == "\"chatcompletions\"")
    }

    test("APIMode.Responses serialises to \"responses\"") {
        assert(write[APIMode](APIMode.Responses) == "\"responses\"")
    }

    test("APIMode round-trips ChatCompletions") {
        assert(read[APIMode]("\"chatcompletions\"") == APIMode.ChatCompletions)
    }

    test("APIMode round-trips Responses") {
        assert(read[APIMode]("\"responses\"") == APIMode.Responses)
    }

    test("APIMode unknown value falls back to ChatCompletions") {
        assert(read[APIMode]("\"unknown\"") == APIMode.ChatCompletions)
    }

    test("APIMode is case-insensitive on read") {
        assert(read[APIMode]("\"RESPONSES\"") == APIMode.Responses)
        assert(read[APIMode]("\"ChatCompletions\"") == APIMode.ChatCompletions)
    }

    // ── AppSettings JSON round-trip ───────────────────────────────────────────

    test("AppSettings default apiMode is ChatCompletions") {
        assert(AppSettings().apiMode == APIMode.ChatCompletions)
    }

    test("AppSettings serialises apiMode as lowercase string in settings file") {
        val path     = java.nio.file.Files.createTempFile("settings-test", ".json")
        val store    = SettingsStore(path)
        val saved    = store.save(AppSettings(apiMode = APIMode.ChatCompletions))
        val raw      = java.nio.file.Files.readString(path)
        val apiField = saved.apiMode
        assert(raw.contains("chatcompletions"),
            s"Expected 'chatcompletions' in saved JSON but got: $raw (saved.apiMode=$apiField)")
        path.toFile.delete()
    }

    test("AppSettings round-trips with apiMode = Responses") {
        val original = AppSettings(apiMode = APIMode.Responses)
        val json     = write(original)
        val parsed   = read[AppSettings](json)
        assert(parsed.apiMode == APIMode.Responses)
    }

    test("AppSettings with missing apiMode field falls back to ChatCompletions") {
        val json   = """{"theme":"light","showStatusLine":false,"serverUrl":"http://localhost:1234","modelName":"test-model","maxIterations":20,"contextBudgetTokens":8000}"""
        val parsed = read[AppSettings](json)
        assert(parsed.apiMode == APIMode.ChatCompletions)
    }

    // ── SettingsStore normalize ───────────────────────────────────────────────

    test("SettingsStore normalize clamps unknown theme to light") {
        val path  = java.nio.file.Files.createTempFile("settings-test", ".json")
        val store = SettingsStore(path)
        val saved = store.save(AppSettings(theme = "neon"))
        assert(saved.theme == "light")
        path.toFile.delete()
    }

    test("SettingsStore persists and reloads apiMode = Responses") {
        val path  = java.nio.file.Files.createTempFile("settings-test", ".json")
        val store = SettingsStore(path)
        store.save(AppSettings(apiMode = APIMode.Responses))
        val loaded = store.load()
        assert(loaded.apiMode == APIMode.Responses)
        path.toFile.delete()
    }

    test("SettingsStore creates default file when missing") {
        val path  = java.nio.file.Files.createTempFile("settings-test", ".json")
        java.nio.file.Files.delete(path)
        val store  = SettingsStore(path)
        val loaded = store.load()
        assert(loaded == AppSettings())
        assert(java.nio.file.Files.exists(path))
        path.toFile.delete()
    }
}
