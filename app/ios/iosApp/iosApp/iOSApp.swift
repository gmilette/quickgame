import SwiftUI
import FirebaseCore

@main
struct iOSApp: App {
    init() {
        let options = FirebaseOptions(
            googleAppID: "1:000000000000:ios:quickgames",
            gcmSenderID: "000000000000"
        )
        options.apiKey = "fake-emulator-api-key"
        options.projectID = "demo-quickgames"
        FirebaseApp.configure(options: options)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea()
        }
    }
}
