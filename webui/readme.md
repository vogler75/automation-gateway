

# WebUI for Automation Gateway

## Description
This is a beta (V0.0.3) release for tests and reviews.

## Prerequisites

Before getting started, you need to have the following software installed on your machine:

- **Node.js** (version 16.0 or higher)
- **npm** (Node package manager, comes with Node.js)
- **Environment variable** of `GATEWAY_CONFIG_PORT=9999`
	>This is required for the admin API (GraphQL) to be activated.
- **Environment variable** of `GATEWAY_CONFIG=config.json`
	>This is required for the "Save configuration to file" function in the settings. By using this variable with your gateway, it will look for a config.json instead of config.yaml when you run the application, so you need to create the config.json file before doing 'gradle run'.

You can verify the installation of Node.js and npm with the following commands:

```bash
node -v
npm -v
```

## To get started in development:
### 1. Clone the repository:
```bash
git clone https://github.com/your-username/your-repository.git
```
### 2. Navigate to the project folder:
```bash
cd <your-repository>/automation-gateway/webui/dev
```
### 3. Install NPM dependencies:
```bash
npm install
```
### 4. Run the dev server:
```bash
npm run dev
```
### 5. Access the WebUI at:
```
http://localhost:5173/
```
>To build and deploy "production" application, you need to run npm run build. This will create a dist folder and that dist folder needs to run aside a webserver (e.g. express js), just like in the build folder of this repo.

## To get started with the build:
>There's an example in the build folder of webui. But here is the general idea on how to get it started.
### 1. Clone the repository:
```bash
git clone https://github.com/your-username/your-repository.git
```
### 2. Navigate to the project folder:
```bash
cd <your-repository>/automation-gateway/webui/build
```
### 3. Install NPM express web server:
```bash
npm install express
```
### 4. Run the production server:
```js
import express from 'express';
import path from 'path';
import { fileURLToPath } from 'url';

const app = express();
const PORT = process.env.PORT || 3000;

// Get __dirname equivalent in ES modules
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Serve static files from the 'dist' directory (or wherever the build output is)
app.use(express.static(path.join(__dirname, 'dist')));

// Handle all other routes by serving the main `index.html` file, useful for Single Page Applications
app.get('*', (req, res) => {
    res.sendFile(path.join(__dirname, 'dist', 'index.html'));
});

app.listen(PORT, () => {
    console.log(`Server is running on http://localhost:${PORT}`);
});
```
>Maybe remove all my files and recreate with your own dist, but mine should work after installing express.
### 5. Install NPM express web server:
```bash
node server.js
```
### 6. Access the WebUI at:
```
http://localhost:3000/
```
#### Tips:
The folder structure of a build environment should look like:
```perl
my-app/
├── dist/               # Contains your built files (e.g., after running `npm run build`)
│   ├── index.html
│   ├── assets/
│   └── ...other build files
├── server.js           # Express server file
├── package.json
└── node_modules/
```
## To get started in docker:
### Prerequisites
Before getting started, you need to have the following software installed on your machine:
- **docker** 
- **docker-compose**
### 1. Clone the repository:
```bash
git clone https://github.com/your-username/your-repository.git
```
### 2. Navigate to the project folder:
```bash
cd <your-repository>/automation-gateway/webui/docker
```
>In this directory, you can edit the docker-compose.yml file before building it, to change the docker application name and the port used to run the express.js server.
### 3. Build and run the docker container with docker-compose:
```bash
docker-compose up --build -d
```
### 4. Access the WebUI at:
```
http://localhost:PORT/
```
>There are some commands that might help you with docker (if you're new):
```bash
# Show all running container
docker ps -a 
# Start/Stop a specific container
docker start <container id>
docker stop <container id>
# Remove a specific container (must be stopped)
docker rm <conatiner id>
```
# Changelogs 
### Changelog V0.0.3
- Save configuration to config file works
	- (see requirements)
- Changed login screen to include "PORT" in the endpoint target field.
	- IP:PORT has to be provided

### Changelog V0.0.2

- Added entries to log level dropdown of components creation
- Changed format of MQTT driver from XML to raw and added SparkPlugB
- Harmonized distance on the creation form items of components
- Harmonized colors for Frankenstein green instead of default Ant Design blue
- Created settings component screen that can handle:
  - Save configuration to config file (in progress)
  - Create and delete temporary access token
  - On-demand query/response area within the browser
- Added 'Show Logs' button on cards to display history of last 50 messages, with refresh every 5 seconds
- Changed click trigger event instead of hover on the action button
- Added a /docker directory that contain a way to deploy as a container
