FROM saschpe/android-sdk:37.2-jdk17.0.20_8 AS build
WORKDIR /workspace
COPY . .

# The source modules are kept targetSdk 35 for runtime compatibility;
# compile against API 37 required by current AndroidX/Compose/CameraX.
RUN find . -name build.gradle.kts -type f -exec sed -i 's/compileSdk = 35/compileSdk = 37/g' {} +

RUN java -version \
    && echo "ANDROID_HOME=$ANDROID_HOME" \
    && test -f "$ANDROID_HOME/platforms/android-37/android.jar" \
    && which apksigner

RUN curl -fL --retry 3 -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-9.6.1-bin.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && /opt/gradle-9.6.1/bin/gradle --version

ARG LINKNAV_BASE_URL=https://api.linknav.invalid
RUN /opt/gradle-9.6.1/bin/gradle --no-daemon --stacktrace :app:assembleDebug -PLINKNAV_BASE_URL="$LINKNAV_BASE_URL"

RUN mkdir -p /apk \
    && cp app/build/outputs/apk/debug/app-debug.apk /apk/LINKNAV-debug.apk \
    && apksigner verify --verbose /apk/LINKNAV-debug.apk \
    && sha256sum /apk/LINKNAV-debug.apk > /apk/LINKNAV-debug.apk.sha256

FROM python:3.12-slim
WORKDIR /apk
COPY --from=build /apk/ /apk/
EXPOSE 8080
CMD ["python3","-m","http.server","8080","--bind","0.0.0.0"]
