echo "This script will deploy to ec2"

set -e
sbt assembly

export EC2_HOSTNAME=nestor.3dr.com

./ssh-ec2 sudo skill java

echo
echo Copying up new version
echo

# we send up src as a super skanky hack because our assembly still accidentally references
# src/main/webapp/WEB-INF
rsync -avz -e "ssh -l ubuntu -i /home/kevinh/.ssh/id_dsa-3dr" drone-mysql.sh src target/scala-2.10/apihub-assembly-*.jar ubuntu@$EC2_HOSTNAME:

rsync -avz -e "ssh -l ubuntu -i /home/kevinh/.ssh/id_dsa-3dr" S98nestor-startup ubuntu@$EC2_HOSTNAME:/tmp
./ssh-ec2 sudo mv /tmp/S98nestor-startup /etc/rc2.d

TAGNAME=deploy-`date +%F-%H%M%S`
echo "Tagging new deployment: $TAGNAME"
git tag -a $TAGNAME -m deployed
git push --tags

echo
echo "Starting new version...."
./ssh-ec2 /etc/rc2.d/S98nestor-startup
