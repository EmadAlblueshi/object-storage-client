#!/bin/sh
# TEST-ONLY bootstrap for the opt-in Ceph RGW test backend (CephContainer), run as the container's
# entrypoint in the official quay.io/ceph/ceph image: a single-node cluster with one monitor, one
# OSD on the in-memory memstore object store, and one radosgw (HTTP on 7480, HTTPS on 7443 with the
# test-only certificate). Authentication between daemons is off and nothing is persisted. Never use
# this for anything but tests.
#
# Environment: ACCESS_KEY and SECRET_KEY of the S3 user; REGION, the zonegroup name, which RGW
# accepts as the LocationConstraint. Prints "RGW READY" when S3 requests can be served.
set -e
FSID=7c1d3f4e-0000-4000-8000-000000000001
REGION="${REGION:-test-west-1}"
mkdir -p /etc/ceph /var/lib/ceph/mon/ceph-a /var/lib/ceph/osd/ceph-0 /var/run/ceph /var/log/ceph
cat > /etc/ceph/ceph.conf <<CONF
[global]
fsid = $FSID
mon host = 127.0.0.1:6789
mon initial members = a
auth cluster required = none
auth service required = none
auth client required = none
osd pool default size = 1
osd pool default min size = 1
osd pool default pg num = 8
osd pool default pgp num = 8
osd pool default pg autoscale mode = off
mon allow pool size one = true
mon warn on pool no redundancy = false
mon max pg per osd = 1000
osd crush chooseleaf type = 0
osd objectstore = memstore
memstore device bytes = 4294967296
log to stderr = false
[client.rgw]
rgw frontends = beast port=7480 ssl_port=7443 ssl_certificate=/etc/ceph/tls.crt ssl_private_key=/etc/ceph/tls.key
CONF

# Monitor
ceph-authtool --create-keyring /tmp/mon.keyring --gen-key -n mon. --cap mon 'allow *' >/dev/null
monmaptool --create --add a 127.0.0.1:6789 --fsid $FSID /tmp/monmap >/dev/null
ceph-mon --mkfs -i a --monmap /tmp/monmap --keyring /tmp/mon.keyring
ceph-mon -i a

# OSD
OSD_UUID=$(cat /proc/sys/kernel/random/uuid)
ID=$(ceph osd new "$OSD_UUID")
ceph-osd -i "$ID" --mkfs --osd-uuid "$OSD_UUID" >/dev/null 2>&1
ceph-osd -i "$ID"
for i in $(seq 1 120); do ceph osd stat 2>/dev/null | grep -q "1 up" && break; sleep 0.5; done

# A realm and a zonegroup named after the region: its api_name, which RGW checks LocationConstraint against
radosgw-admin realm create --rgw-realm=test --default >/dev/null
radosgw-admin zonegroup create --rgw-realm=test --rgw-zonegroup="$REGION" --master --default >/dev/null
radosgw-admin zone create --rgw-zonegroup="$REGION" --rgw-zone="$REGION-a" --master --default >/dev/null
radosgw-admin period update --commit >/dev/null

# RGW and the S3 user
radosgw -n client.rgw
for i in $(seq 1 240); do curl -s -o /dev/null http://127.0.0.1:7480/ && break; sleep 0.5; done
radosgw-admin user create --uid tester --display-name tester --access-key "$ACCESS_KEY" --secret "$SECRET_KEY" >/dev/null
echo "RGW READY"
exec tail -f /dev/null
