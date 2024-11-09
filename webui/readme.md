# WebUI for Automation Gateway

## Description
This is a beta1 release for tests and reviews.

## Prerequisites

Before getting started, you need to have the following software installed on your machine:

- **Node.js** (version 16.0 or higher)
- **npm** (Node package manager, comes with Node.js)

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
const express = require('express');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;

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

# More details to come on explaining the available and unavailable features by the webui.