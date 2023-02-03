pipeline {
    agent {
        label 'docker-host'
    }
    options {
        disableConcurrentBuilds()
        disableResume()
    }

    parameters {
        choice(
            name: 'DB_ENGINE',
            choices: ['mysql', 'oracleXE', 'postgresql'],
            description: 'Choose the database engine to use'
        )
        string name: 'ENVIRONMENT_NAME', trim: true     
        password defaultValue: '', description: 'Password to use for MySQL container - root user', name: 'MYSQL_PASSWORD'
        string name: 'MYSQL_PORT', trim: true  

        booleanParam(name: 'SKIP_STEP_1', defaultValue: false, description: 'STEP 1 - RE-CREATE DOCKER IMAGE')
    }
  
    stages {
         stage('Check parameters') {
            steps {     
              script {
                def port = Integer.parseInt(params.MYSQL_PORT)
                if (!(port >= 0 && port <= 65535)) {
                    throw new IllegalArgumentException("Invalid port number: " + params.MYSQL_PORT + ". Port must be between 0 and 65535.")
                }
                // Empty root password forbidden
                String password = params.MYSQL_PASSWORD
                if (password.trim().isEmpty()) {
                  throw new IllegalArgumentException("The MYSQL_PASSWORD parameter cannot be empty.")
                }
              }
            }
        }
        stage('Checkout GIT repository') {
            steps {     
              script {
                git branch: 'main',
                url: 'https://github.com/thimagu2023/oracle.git'
              }
            }
        }
        stage('Create latest Docker image') {
            steps {     
              script {
                if (!params.SKIP_STEP_1){    
                    echo "Creating docker image with name $params.ENVIRONMENT_NAME using port: $params.MYSQL_PORT"
                    sh """
                    sed 's/<PASSWORD>/$params.MYSQL_PASSWORD/g' pipelines/include/create_developer.template > pipelines/include/create_developer.sql
                    """

                    if (params.DB_ENGINE == 'mysql') {
                        sh """
                        docker build pipelines/ -t $params.ENVIRONMENT_NAME:latest -f Dockerfile.mysql
                        """
                    } else if (params.DB_ENGINE == 'oracleXE') {
                        sh """
                        docker build pipelines/ -t $params.ENVIRONMENT_NAME:latest -f Dockerfile.oracle
                        """
                    } else if (params.DB_ENGINE == 'postgres') {
                        sh """
                        docker build pipelines/ -t $params.ENVIRONMENT_NAME:latest -f Dockerfile.postgres
                        """
                    }

                }else{
                    echo "Skipping STEP1"
                }
              }
            }
        }
        stage('Start new container using latest image and create user') {
            steps {     
              script {
                
                def dateTime = (sh(script: "date +%Y%m%d%H%M%S", returnStdout: true).trim())
                def containerName = "${params.ENVIRONMENT_NAME}_${dateTime}"
                
                if (params.DB_ENGINE == 'mysql') {
                    sh """
                    docker run -itd --name ${containerName} --rm -e MYSQL_ROOT_PASSWORD=$params.MYSQL_PASSWORD -p $params.MYSQL_PORT:3306 $params.ENVIRONMENT_NAME:latest
                    """
                    sh """
                    while ! nc -z localhost 3306; do sleep 0.1;done 
                    """
                    sh """
                    docker exec ${containerName} /bin/bash -c 'mysql --user="root" --password="$params.MYSQL_PASSWORD" < /scripts/create_developer.sql'
                    """

                    echo "Docker container created: $containerName"
                } else if (params.DB_ENGINE == 'postgres') {
                    
                    sh """
                    docker run -itd --name ${containerName}-postgres --rm -e POSTGRES_PASSWORD=$params.POSTGRES_PASSWORD -p 5432:5432 postgres
                    """
                    sh """
                    while ! nc -z localhost 5432; do sleep 0.1;done
                    """
                } else if (params.DB_ENGINE == 'oracleXE') {
                    // run postgres container
                }

              }
            }
        }
    }

}
