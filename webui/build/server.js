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
