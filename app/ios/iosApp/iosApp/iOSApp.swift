import SwiftUI
import FirebaseCore

@main
struct iOSApp: App {
    init() {
        let options = FirebaseOptions(
            googleAppID: "1:1047416669336:ios:9fae198807f2501804fced",
            gcmSenderID: "1047416669336"
        )
        options.apiKey = "AIzaSyDfyOdfDWQJVus6QU8VerkRfaB54gkj6JA"
        options.projectID = "quickgame-336e4"
        FirebaseApp.configure(options: options)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea()
        }
    }
}
