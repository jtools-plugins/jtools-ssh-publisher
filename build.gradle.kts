import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.10"
    id("org.jetbrains.intellij.platform") version "2.7.2"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "com.lhstack"
version = "1.1.8"


repositories {
    intellijPlatform {
        defaultRepositories()
    }
    mavenLocal()
    maven("https://maven.aliyun.com/repository/public/")
    mavenCentral()
}


dependencies {
    intellijPlatform{
        intellijIdeaCommunity("2022.3")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
    implementation("org.apache.sshd:sshd-sftp:2.15.0")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
//    implementation(files("/Users/lhstack/.jtools/sdk/sdk.jar"))
    implementation(files("/Users/lhstack/.jtools/sdk/sdk.jar"))
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

configurations.configureEach {
    // IntelliJ 平台已经提供 SLF4J 绑定，插件再打入 API/bridge 会触发类加载冲突。
    exclude(group = "org.slf4j", module = "slf4j-api")
    exclude(group = "org.slf4j", module = "jcl-over-slf4j")
}
tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "11"
        targetCompatibility = "11"
        options.encoding = "UTF-8"
    }
    withType<JavaExec> {
        jvmArgs("-Dfile.encoding=UTF-8")
    }

    withType<Jar>(){
        archiveBaseName = "jtools-ssh-publisher"
    }

    withType<ShadowJar> {
        // IntelliJ Platform 插件生成的 generateManifest 任务会把 MANIFEST.MF 接入 jar 的 rootSpec，
        // shadowJar 追踪输入快照时该文件可能尚未生成，触发 NoSuchFileException。
        // 先显式依赖 generateManifest 保证文件存在，再关闭状态追踪规避不可读输入的快照校验。
        dependsOn("generateManifest")
        doNotTrackState("shadowJar 需读取 generateManifest 产出的 MANIFEST.MF，其路径由平台插件动态管理")
        transform(com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer::class.java)
        transform(com.github.jengelman.gradle.plugins.shadow.transformers.XmlAppendingTransformer::class.java)
        transform(com.github.jengelman.gradle.plugins.shadow.transformers.XmlAppendingTransformer::class.java)
        exclude("META-INF/MANIFEST.MF","META-INF/*.SF","META-INF/*.DSA")
        dependencies {
            exclude(dependency("com.jetbrains.*:.*:.*"))
            exclude(dependency("org.jetbrains.*:.*:.*"))
        }
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions{
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs = listOf("-Xjvm-default=all")
        }
    }

}
tasks.test {
    useJUnitPlatform()
}
