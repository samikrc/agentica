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
            .getOrElse("http://127.0.0.1:8080/?token=dev-token")

        val webView = WebView()
        val root    = BorderPane(webView)

        webView.getEngine.setOnAlert((event: WebEvent[String]) => {
            val alert = Alert(Alert.AlertType.INFORMATION, event.getData, ButtonType.OK)
            alert.initOwner(stage)
            alert.showAndWait()
        })

        webView.getEngine.setConfirmHandler(new Callback[String, java.lang.Boolean] {
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
