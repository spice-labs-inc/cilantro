# Derived fetch image: pinned dotnet SDK digest + mono-devel (ilasm).
# The corpus fetch must run as the invoking user (uid/gid) so files written
# to the mounted volume are owned correctly; apt-get therefore happens at
# image build time, not at run time.
FROM mcr.microsoft.com/dotnet/sdk:8.0@sha256:bb32ba3ba3ea36e38572d9d8db76fa15f7cbf722f3f886e06bca6d528bd4fba8
RUN apt-get update -qq \
 && apt-get install -y -qq --no-install-recommends mono-devel \
 && rm -rf /var/lib/apt/lists/*
