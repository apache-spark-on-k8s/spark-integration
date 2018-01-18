#!/usr/bin/env bash

clone_build_spark() {
  spark_repo=$1
  spark_repo_local_dir=$2
  branch=$3
  pushd .

  # clone spark distribution if needed.
  if [ -d "$spark_repo_local_dir" ];
  then
    (cd $spark_repo_local_dir && git fetch origin $branch);
  else
    mkdir -p $spark_repo_local_dir;
    git clone -b $branch --single-branch $spark_repo $spark_repo_local_dir;
  fi
  cd $spark_repo_local_dir
  git checkout -B $branch origin/$branch
  ./dev/make-distribution.sh --tgz -Phadoop-2.7 -Pkubernetes -DskipTests;
  SPARK_TGZ=$(find $spark_repo_local_dir -name spark-*.tgz)

  popd
}
