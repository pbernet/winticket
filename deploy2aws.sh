#!/bin/bash
set -x

# set the version label
VERSION_LABEL=v35

PKG=winticket_pkg

# Build the docker image
sbt docker:stage

# TODO Is this workaround still needed: Currently the generated  ./target/Docker/Dockerfile needs to be patched when deployed on aws: Replace this line
# Produces: COPY stage /
sed -i '' '4s/.*/COPY stage \//' ./target/Docker/Dockerfile
# Remove missplaced file
rm -f  ./target/docker/stage/Dockerfile

# Copy Dockerrun.aws.json to target/docker
cp Dockerrun.aws.json ./target/docker

# Zip the files
cd ./target/docker
zip -r $PKG.zip *

echo current path is: `pwd`

# -- AWS --
# Upload the image
echo Uploading to S3...
aws s3api put-object --bucket winticket --key $PKG.zip --body $PKG.zip

# Create a new application version
echo Creating new application version $VERSION_LABEL...
aws elasticbeanstalk create-application-version --application-name winticket_app --version-label $VERSION_LABEL --description winticket_app_$VERSION_LABEL --source-bundle S3Bucket=winticket,S3Key=$PKG.zip --auto-create-application


# Note: The environment winticketApp-env has bean created with the AWS GUI
echo Deploy the image....
aws elasticbeanstalk update-environment --environment-name winticket --version-label $VERSION_LABEL
