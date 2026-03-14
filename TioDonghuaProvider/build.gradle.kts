// use an integer for version numbers
version = 1

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

    iconUrl = "https://tiodonghua.com/wp-content/uploads/2021/07/cropped-TICO2-192x192.png"
}

android {
    namespace = "com.example.tiodonghua"
}
