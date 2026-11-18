package agentica

import javafx.application.Application
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.concurrent.Worker
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.web.PopupFeatures
import javafx.scene.web.WebEngine
import javafx.scene.web.WebEvent
import javafx.scene.web.WebView
import javafx.stage.Stage
import javafx.util.Callback

/** 
 *  Minimal JavaFX desktop shell for Agentica.
 *  It embeds the existing web UI in a [[javafx.scene.web.WebView]] and loads an
 *  already-running backend URL.
 */
class DesktopLauncher extends Application
{
    /** 
     *  Creates the WebView window, wires JavaScript dialog handlers, and loads the target URL.
     *  @param stage  Primary JavaFX application window.
     */
    override def start(stage: Stage): Unit =
    {
        val url = sys.props.get("agentica.desktop.url")
            .orElse(sys.env.get("AGENTICA_DESKTOP_URL"))
            .getOrElse {
                // Matches BackendServer's own default when AGENTICA_PORT is unset.
                val port = sys.env.getOrElse("AGENTICA_PORT", "11211")
                // Mirror AGENTICA_HOST for the connect address; wildcard binds
                // are listen addresses, not connect targets — a 0.0.0.0
                // backend is still reached via loopback.
                val host = sys.env.getOrElse("AGENTICA_HOST", "127.0.0.1") match
                {
                    case "0.0.0.0" | "::" => "127.0.0.1"
                    case h => h
                }
                // This JVM is separate from the backend's — it can only
                // advertise an explicitly configured token. When the backend
                // generated a per-launch token, the desktop URL stays tokenless
                // (the backend prints that token to stderr instead).
                val token = sys.env.get("AGENTICA_TOKEN").filter(_.nonEmpty).getOrElse("")
                s"http://$host:$port/#token=$token"
            }

        val webView = WebView()
        val root    = BorderPane(webView)

        webView.getEngine.setCreatePopupHandler(new Callback[PopupFeatures, WebEngine]
        {
            override def call(features: PopupFeatures): WebEngine =
            {
                val popupView   = WebView()
                val popupRoot   = BorderPane(popupView)
                val popupStage  = Stage()
                popupStage.setTitle("Agentica — Debug Log")
                popupStage.setScene(Scene(popupRoot, 1200, 800))
                popupStage.show()
                popupView.getEngine
            }
        })

        webView.getEngine.setOnAlert((event: WebEvent[String]) => {
            val alert = Alert(Alert.AlertType.INFORMATION, event.getData, ButtonType.OK)
            alert.initOwner(stage)
            alert.showAndWait()
        })

        webView.getEngine.setConfirmHandler(new Callback[String, java.lang.Boolean]
        {
            override def call(message: String): java.lang.Boolean =
            {
                val alert = Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL)
                alert.initOwner(stage)
                alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK
            }
        })

        stage.setTitle("Agentica")
        stage.setScene(Scene(root, 1200, 800))
        stage.show()
        webView.getEngine.load(url)
    }
}

/** 
 *  Command-line entry point for the JavaFX desktop shell.
 *  @param args  Optional first argument containing the backend UI URL.
 */
object DesktopLauncher
{
    /** 
     *  Starts JavaFX, optionally using the first argument as the URL to load.
     */
    def main(args: Array[String]): Unit =
    {
        args.headOption.foreach(url => System.setProperty("agentica.desktop.url", url))
        Application.launch(classOf[DesktopLauncher], args*)
    }
}
