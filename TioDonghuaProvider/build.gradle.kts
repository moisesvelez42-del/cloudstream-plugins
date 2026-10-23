// use an integer for version numbers
version = 5

cloudstream {
    description = "Donghua sub español gratis en HD"
    authors = listOf("community")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    // Anime / Donghua content
    tvTypes = listOf("Anime")

    iconUrl = "https://tiodonghua.lat/wp-content/uploads/2020/09/cropped-isotipo-192x192.png"
}

android {
    namespace = "com.example.tiodonghua"
}
