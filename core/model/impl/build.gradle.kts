plugins {
    id("yatagan.implementation-artifact")
}

dependencies {
    api(project(":core:model:api"))

    implementation(project(":validation:format"))
    implementation(project(":base:impl"))
}