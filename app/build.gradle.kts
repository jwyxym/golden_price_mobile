plugins {
	id("com.android.application");
}

dependencies {
	testImplementation("junit:junit:4.13.2");
}

android {
	namespace = "com.goldenprice.mobile";
	compileSdk = 36;

	defaultConfig {
		applicationId = "com.goldenprice.mobile";
		minSdk = 26;
		targetSdk = 36;
		versionCode = 1;
		versionName = "0.1.0";
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17;
		targetCompatibility = JavaVersion.VERSION_17;
	}
}
