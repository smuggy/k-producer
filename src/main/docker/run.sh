#!/usr/bin/env sh
set -x

#/app/k-producer-boot-__app_version__/bin/k-producer --spring.config.location=file:/config/application.yaml $@
/app/k-producer-boot-__app_version__/bin/k-producer $@
