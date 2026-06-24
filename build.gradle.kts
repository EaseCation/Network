allprojects {
    plugins.apply("java-library")
    val javaLanguageVersion = JavaLanguageVersion.of(25)
    the<JavaPluginExtension>().apply {
        toolchain {
            languageVersion = javaLanguageVersion
        }
    }
}
