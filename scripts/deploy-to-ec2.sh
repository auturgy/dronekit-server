echo "This script will deploy to ec2"

set -e
SRCDIR=`readlink -f ..`
pushd $SRCDIR
sbt clean assembly
popd

export EC2_HOSTNAME=ichi.ardupilot.org
SSH_USER=kevinh
export SSH_OPTS="ssh -l $SSH_USER"

echo "Killing old daemon"
./ssh-ec2 skill java

echo
echo Copying up new version
echo

# we send up src as a super skanky hack because our assembly still accidentally references
# src/main/webapp/WEB-INF
./ssh-ec2 cp apihub-assembly-\*.jar backup || true
./ssh-ec2 mkdir -p src
rsync -avz -e "$SSH_OPTS" $SRCDIR/target/scala-2.10/apihub-assembly-*.jar $SSH_USER@$EC2_HOSTNAME:
rsync -avz -e "$SSH_OPTS" $SRCDIR/src/main/webapp $SSH_USER@$EC2_HOSTNAME:src/main
rsync -avz -e "$SSH_OPTS" $SRCDIR/ardupilot/Tools/LogAnalyzer $SSH_USER@$EC2_HOSTNAME:

rsync -avz -e "$SSH_OPTS" ./S98nestor-startup $SSH_USER@$EC2_HOSTNAME:/tmp
# ./ssh-ec2 sudo mv /tmp/S98nestor-startup /etc/rc2.d

TAGNAME=deploy-`date +%F-%H%M%S`
echo "Tagging new deployment: $TAGNAME"
git tag -a $TAGNAME -m deployed
git push --tags

echo
echo "Starting new version...."
./ssh-ec2 /etc/rc2.d/S98nestor-startup

# Tell newrelic we just pushed a new load
# curl -H "x-api-key:apikeyfixme" -d "deployment[app_name]=My Application" -d "deployment[user]=$USER" -d "deployment[description]=deploy-to-ec2" https://api.newrelic.com/deployments.xml
