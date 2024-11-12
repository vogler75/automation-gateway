# Step 1: Use an official Node.js image as a base
FROM node:18-alpine

# Step 2: Set working directory inside the container
WORKDIR /app

# Step 3: Copy package.json and package-lock.json
COPY package.json package-lock.json ./

# Step 4: Install dependencies (including server dependencies)
RUN npm install --production

# Step 5: Copy the built React app (dist folder)
COPY dist /app/dist

# Step 6: Copy server.js (or any other necessary server files)
COPY server.js /app/

# Step 7: Expose the port your app will run on (you can choose any port, e.g., 3000)
EXPOSE 3000

# Step 8: Start the server
CMD ["node", "server.js"]
