FROM mingc/android-build-box:latest AS build
WORKDIR /workspace
COPY . .

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk

RUN test -f /opt/android-sdk/platforms/android-35/android.jar
RUN test -x /opt/android-sdk/build-tools/37.0.0/apksigner

RUN curl -fL --retry 3 -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-9.6-bin.zip     && unzip -q /tmp/gradle.zip -d /opt     && /opt/gradle-9.6/bin/gradle --version

ARG LINKNAV_BASE_URL=https://api.linknav.invalid
RUN /opt/gradle-9.6/bin/gradle --no-daemon --stacktrace :app:assembleDebug -PLINKNAV_BASE_URL="$LINKNAV_BASE_URL"

RUN mkdir -p /apk     && cp app/build/outputs/apk/debug/app-debug.apk /apk/LINKNAV-debug.apk     && /opt/android-sdk/build-tools/37.0.0/apksigner verify --verbose /apk/LINKNAV-debug.apk     && sha256sum /apk/LINKNAV-debug.apk > /apk/LINKNAV-debug.apk.sha256

FROM python:3.12-slim
WORKDIR /apk
COPY --from=build /apk/ /apk/
EXPOSE 8080
CMD ["python3","-m","http.server","8080","--bind","0.0.0.0"]
