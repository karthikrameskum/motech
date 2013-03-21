#!/bin/bash

source /tmp/functions.sh

while getopts "d:b:e:" opt; do
	case $opt in
	d)
        CHROOT_DIR=$OPTARG
	;;
	b)
	    BUILD_DIR=$OPTARG
	;;
    e)
	    ERROR_LOG=$OPTARG
	;;
    p)
        PORT=$OPTARG
    ;;
    esac
done

PORT=${PORT-8099}

if [ -z $ERROR_LOG ]; then
    ERROR_LOG=$BUILD_DIR/err.log
fi

if [ -z $CHROOT_DIR ]; then
    echo "Chroot dir not defined" > $ERROR_LOG
    exit 1
fi

BASE_PACKAGE=`ls $BUILD_DIR | grep motech-base`

if [ ! -f $BUILD_DIR/$BASE_PACKAGE ]; then
    echo "Base package does not exist: $BASE_PACKAGE" > $ERROR_LOG
    exit 1
fi

MAKEROOT=""
if [[ $EUID -ne 0 ]];then
    MAKEROOT="sudo"
fi

CHROOT="$MAKEROOT chroot $CHROOT_DIR"

init_data

$CHROOT service motech-default stop

# Make sure some dirs are empty, so they can be removed
$CHROOT rm -rf /var/log/motech/*

# Remove motech
$CHROOT yum remove motech-base -y

for dir in $MOTECH_OWNED; do
    if [ -d $CHROOT_DIR/$dir ]; then
        echo "$dir still exists after uninstall" > $ERROR_LOG
        purge_motech
        exit 1
    fi
done

exit 0 # Victory