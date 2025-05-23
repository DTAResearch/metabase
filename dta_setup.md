### Setup of DTA => Build custom image
docker build --build-arg MB_EDITION=oss --build-arg VERSION=latest -t metabase-custom .

### Then run metabase container
